package pl.skidam.automodpack.client.ui.screen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.change.PlatformReferences;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.PreservationVault;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.utils.cache.PlatformMetadataCache;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** One browser for every file AutoModpack preserved before a destructive change. */
public final class PreservationVaultScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 500;
	private static final int ROW_HEIGHT = 52;

	private final Screen parent;
	private final InstalledModpackController controller;
	private final Runnable closedCallback;
	private List<PreservationVault.Snapshot> snapshots;
	private String selectedClaimId;
	private String pendingDeleteClaimId;
	private final Map<String, List<PlatformReferences.Page>> platformPagesByClaimId = new HashMap<>();
	private final Map<String, String> packNames = new HashMap<>();
	private final Set<String> activePacks = new HashSet<>();
	private boolean loading;
	private boolean busy;
	private boolean restoreFailed;
	private boolean presentingFailure;
	private boolean closed;
	private boolean lastResultRestore;
	private Path lastResult;
	private int page;
	private int pageSize = 1;
	private Future<?> work;

	public PreservationVaultScreen(Screen parent, InstalledModpackController controller, Runnable closedCallback) {
		super(VersionedText.translatable("automodpack.vault.title"));
		this.parent = parent;
		this.controller = controller;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		if (!loading && snapshots == null) load();
		List<PreservationVault.Claim> claims = claims();
		PreservationVault.Claim selected = selected();
		boolean deleteArmed = selected != null && selected.claimId().equals(pendingDeleteClaimId);
		MutableComponent deleteLabel = VersionedText.translatable("automodpack.vault.delete");
		if (deleteArmed) deleteLabel.withStyle(ChatFormatting.RED);
		List<ActionRow> actions = new ArrayList<>();
		actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				primaryAction(VersionedText.translatable("automodpack.vault.restore"), press -> restore()),
				optionalAction(VersionedText.translatable("automodpack.vault.saveCopy"), press -> saveCopy()),
				optionalAction(deleteLabel, press -> delete())));
		List<PlatformReferences.Page> platformPages = selected == null ? List.of() : platformPagesByClaimId.getOrDefault(selected.claimId(), List.of());
		if (!platformPages.isEmpty()) actions.add(platformRow(platformPages));
		if (lastResult != null && !restoreFailed) {
			String key = lastResultRestore ? "automodpack.vault.restoredTo" : "automodpack.vault.savedTo";
			String confirmation = truncateToWidth(this.font, VersionedText.translatable(key, displayPath(lastResult)).getString(), panelWidth(PANEL_WIDTH) - 12);
			actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, disabledAction(VersionedText.literal(confirmation).withStyle(ChatFormatting.GREEN))));
		}
		actions.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), press -> back())));
		pageSize = rowsPerPage(actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actions.toArray(ActionRow[]::new)));
		int pageCount = Math.max(1, (claims.size() + pageSize - 1) / pageSize);
		if (pageCount > 1) {
			actions.add(1, navigationRow(pageCount));
			pageSize = rowsPerPage(actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actions.toArray(ActionRow[]::new)));
			pageCount = Math.max(1, (claims.size() + pageSize - 1) / pageSize);
		}
		page = Math.max(0, Math.min(pageCount - 1, page));
		if (pageCount > 1) actions.set(1, navigationRow(pageCount));
		int start = page * pageSize;
		int width = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		for (int index = start; index < Math.min(claims.size(), start + pageSize); index++) {
			PreservationVault.Claim claim = claims.get(index);
			int y = 66 + (index - start) * ROW_HEIGHT;
			String filename = fileName(claim.originalPath()) + "  " + UiFormat.formatSize(claim.size());
			Button select = buttonWidget(x, y, width, 20,
					VersionedText.literal(truncateToWidth(this.font, filename, width - 12)).withStyle(claim.claimId().equals(selectedClaimId) ? ChatFormatting.GREEN : ChatFormatting.WHITE), press -> select(claim));
			select.active = !busy && !loading;
			setTooltip(select, VersionedText.translatable("automodpack.vault.rowTooltip", claim.originalPath()));
			this.addRenderableWidget(select);
		}

		List<Button> actionButtons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actions.toArray(ActionRow[]::new));
		Button restore = actionButtons.get(0);
		Button saveCopy = actionButtons.get(1);
		Button delete = actionButtons.get(2);
		restore.active = !busy && canRestore(selected);
		setTooltip(restore, restoreTooltip(selected));
		saveCopy.active = !busy && selected != null;
		setTooltip(saveCopy, VersionedText.translatable("automodpack.vault.saveCopyMoves"));
		delete.active = !busy && selected != null;
		if (pageCount > 1) {
			actionButtons.get(3).active = !busy && page > 0;
			renderAsPlainText(actionButtons.get(4));
			actionButtons.get(5).active = !busy && page + 1 < pageCount;
		}
	}

	/** The gate names itself: why Restore is unavailable for this row, or what it will do. */
	private MutableComponent restoreTooltip(PreservationVault.Claim selected) {
		if (selected == null) return VersionedText.translatable("automodpack.vault.restorePickFirst");
		if (!activePacks.contains(selected.modpackId())) return VersionedText.translatable("automodpack.vault.restoreInactivePack");
		if (selected.sourceRoot() != UpdatePlan.Root.GAME_DIR) return VersionedText.translatable("automodpack.vault.restoreManagedFiles");
		return VersionedText.translatable("automodpack.vault.restoreApplies", selected.originalPath());
	}

	private boolean canRestore(PreservationVault.Claim selected) {
		return selected != null && activePacks.contains(selected.modpackId()) && selected.sourceRoot() == UpdatePlan.Root.GAME_DIR;
	}

	private void load() {
		loading = true;
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				List<PreservationVault.Snapshot> loaded = controller.preservedFiles();
				this.minecraft.execute(() -> loaded(loaded));
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void loaded(List<PreservationVault.Snapshot> loaded) {
		if (closed) return;
		snapshots = List.copyOf(loaded);
		packNames.clear();
		activePacks.clear();
		for (InstalledModpackController.Pack pack : controller.installed()) {
			packNames.put(pack.modpackId(), pack.name());
			if (pack.active()) activePacks.add(pack.modpackId());
		}
		loading = false;
		busy = false;
		if (selectedClaimId != null && claims().stream().noneMatch(claim -> claim.claimId().equals(selectedClaimId))) selectedClaimId = null;
		platformPagesByClaimId.keySet().retainAll(claims().stream().map(PreservationVault.Claim::claimId).toList());
		pendingDeleteClaimId = null;
		resolvePlatformPages(selected());
		rebuild();
	}

	private List<PreservationVault.Claim> claims() {
		List<PreservationVault.Claim> claims = new ArrayList<>();
		if (snapshots == null) return claims;
		for (PreservationVault.Snapshot snapshot : snapshots) claims.addAll(snapshot.claims());
		claims.sort(Comparator.comparing(PreservationVault.Claim::preservedAt).reversed().thenComparing(PreservationVault.Claim::originalPath).thenComparing(PreservationVault.Claim::modpackId));
		return claims;
	}

	private PreservationVault.Claim selected() {
		if (selectedClaimId == null) return null;
		return claims().stream().filter(claim -> claim.claimId().equals(selectedClaimId)).findFirst().orElse(null);
	}

	private static int rowsPerPage(int actionTop) {
		return Math.max(1, (actionTop - 60) / ROW_HEIGHT);
	}

	private ActionRow navigationRow(int pageCount) {
		return actionRow(ActionAreaLayout.RowKind.NAVIGATION,
				navigationAction(VersionedText.translatable("automodpack.ui.previous"), press -> changePage(-1)),
				disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount)),
				navigationAction(VersionedText.translatable("automodpack.ui.next"), press -> changePage(1)));
	}

	private void select(PreservationVault.Claim claim) {
		selectedClaimId = claim.claimId();
		pendingDeleteClaimId = null;
		restoreFailed = false;
		lastResult = null;
		resolvePlatformPages(claim);
		rebuild();
	}

	/** Cache-only Modrinth/CurseForge page lookup for the selected claim; no buttons when nothing was cached. */
	private void resolvePlatformPages(PreservationVault.Claim claim) {
		if (claim == null || platformPagesByClaimId.containsKey(claim.claimId())) return;
		DownloadClient.NET_EXECUTOR.execute(() -> {
			List<PlatformReferences.Page> pages = cachedPlatformPages(claim.objectHash());
			this.minecraft.execute(() -> {
				if (closed) return;
				platformPagesByClaimId.put(claim.claimId(), pages);
				rebuild();
			});
		});
	}

	private ActionRow platformRow(List<PlatformReferences.Page> pages) {
		List<ActionDefinition> definitions = new ArrayList<>();
		for (PlatformReferences.Page page : pages) definitions.add(optionalAction(VersionedText.translatable("automodpack.browser." + page.platform()), button -> Util.getPlatform().openUri(page.url())));
		return actionRow(ActionAreaLayout.RowKind.AUXILIARY, definitions.toArray(ActionDefinition[]::new));
	}

	private static List<PlatformReferences.Page> cachedPlatformPages(String sha1) {
		try (PlatformMetadataCache cache = PlatformMetadataCache.open(ClientStorage.open(GameDirectory.current()).platformMetadataDirectory())) {
			return PlatformReferences.cachedPages(cache, sha1);
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
	}

	private void changePage(int amount) {
		page = Math.max(0, page + amount);
		selectedClaimId = null;
		pendingDeleteClaimId = null;
		lastResult = null;
		rebuild();
	}

	private void restore() {
		PreservationVault.Claim claim = selected();
		if (claim == null || busy || !canRestore(claim)) return;
		run(() -> controller.restorePreservedFile(claim.modpackId(), claim.claimId()), true);
	}

	private void saveCopy() {
		PreservationVault.Claim claim = selected();
		if (claim == null || busy) return;
		run(() -> controller.savePreservedCopy(claim.modpackId(), claim.claimId()), false);
	}

	private void delete() {
		PreservationVault.Claim claim = selected();
		if (claim == null || busy) return;
		if (!claim.claimId().equals(pendingDeleteClaimId)) {
			pendingDeleteClaimId = claim.claimId();
			rebuild();
			return;
		}
		run(() -> {
			controller.deletePreservedFile(claim.modpackId(), claim.claimId());
			return null;
		}, false);
	}

	private void run(VaultOperation operation, boolean restoreAttempt) {
		busy = true;
		rebuild();
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				Path destination = operation.run();
				List<PreservationVault.Snapshot> refreshed = controller.preservedFiles();
				this.minecraft.execute(() -> {
					lastResult = destination;
					lastResultRestore = restoreAttempt;
					loaded(refreshed);
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e, restoreAttempt));
			}
		});
	}

	private void fail(Exception exception) {
		fail(exception, false);
	}

	private void fail(Exception exception, boolean restoreAttempt) {
		if (closed) return;
		loading = false;
		busy = false;
		restoreFailed = restoreAttempt;
		lastResult = null;
		if (snapshots == null) snapshots = List.of();
		rebuild();
		presentingFailure = true;
		ScreenManager.failure(FailureRequest.of(exception, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
	}

	private void back() {
		if (closed) return;
		closed = true;
		cancelWork();
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	private void cancelWork() {
		Future<?> current = work;
		if (current != null && !current.isDone()) current.cancel(true);
	}

	@Override
	public void removed() {
		if (presentingFailure) {
			presentingFailure = false;
			super.removed();
			return;
		}
		if (!closed) {
			closed = true;
			cancelWork();
			closedCallback.run();
		}
		super.removed();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.vault.title").withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		String description = loading
				? VersionedText.translatable("automodpack.vault.loading").getString()
				: VersionedText.translatable("automodpack.vault.description", claims().size()).getString();
		List<String> descriptionLines = wrapToWidth(this.font, description, this.width - 28, 2);
		for (int index = 0; index < descriptionLines.size(); index++)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(descriptionLines.get(index)).withStyle(ChatFormatting.GRAY), this.width / 2, 28 + index * 12, TextColors.WHITE);
		if (restoreFailed) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.vault.restoreUnavailable").withStyle(ChatFormatting.AQUA), this.width / 2, 52, TextColors.WHITE);
		else if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.vault.working").withStyle(ChatFormatting.YELLOW), this.width / 2, 52, TextColors.WHITE);
		else {
			PreservationVault.Claim armed = selected();
			if (armed != null && armed.claimId().equals(pendingDeleteClaimId))
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.vault.deleteArmed", armed.originalPath()).withStyle(ChatFormatting.RED), this.width / 2, 52, TextColors.WHITE);
			else
				if (armed != null)
					drawCenteredTextWithShadow(matrices, this.font,
							VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.vault.selected", armed.originalPath()).getString(), this.width - 20)).withStyle(ChatFormatting.YELLOW),
							this.width / 2, 52, TextColors.WHITE);
		}
		List<PreservationVault.Claim> claims = claims();
		int start = page * pageSize;
		for (int index = start; index < Math.min(claims.size(), start + pageSize); index++) {
			PreservationVault.Claim claim = claims.get(index);
			int y = 88 + (index - start) * ROW_HEIGHT;
			String metadata = packNames.getOrDefault(claim.modpackId(), claim.modpackId()) + "  |  " + claim.originalPath() + "  |  " + reason(claim.reason());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, metadata, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(UiFormat.formatInstant(claim.preservedAt())).withStyle(ChatFormatting.GRAY), this.width / 2, y + 12, TextColors.WHITE);
		}
		if (!loading && claims.isEmpty() && lastResult == null)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.vault.empty").withStyle(ChatFormatting.GRAY), this.width / 2, 88, TextColors.WHITE);
	}

	private static String displayPath(Path path) {
		Path game = GameDirectory.current().toAbsolutePath().normalize();
		Path absolute = path.toAbsolutePath().normalize();
		if (absolute.startsWith(game)) return game.relativize(absolute).toString().replace('\\', '/');
		return absolute.toString().replace('\\', '/');
	}

	private static String fileName(String originalPath) {
		if (originalPath == null || originalPath.isBlank()) return "";
		Path path = Path.of(originalPath.replace('\\', '/'));
		Path name = path.getFileName();
		return name == null ? originalPath : name.toString();
	}

	private static String reason(PreservationVault.Reason reason) {
		return VersionedText.translatable("automodpack.vault.reason." + reason.name().toLowerCase(Locale.ROOT)).getString();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// Esc is the safe default: while Delete is armed it disarms first instead of leaving the screen.
		if (pendingDeleteClaimId != null) {
			pendingDeleteClaimId = null;
			rebuild();
			return false;
		}
		back();
		return false;
	}

	@FunctionalInterface
	private interface VaultOperation {
		Path run() throws Exception;
	}
}
