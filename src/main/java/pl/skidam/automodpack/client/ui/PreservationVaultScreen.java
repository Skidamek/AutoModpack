package pl.skidam.automodpack.client.ui;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.PreservationVault;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** One browser for every file AutoModpack preserved before a destructive change. */
public final class PreservationVaultScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 430;
	private static final int ROW_HEIGHT = 52;

	private final Screen parent;
	private final ClientStorage storage;
	private final String modpackId;
	private final String modpackName;
	private final boolean activePack;
	private final Runnable closedCallback;
	private PreservationVault.Snapshot snapshot;
	private String selectedClaimId;
	private String pendingDeleteClaimId;
	private boolean loading;
	private boolean busy;
	private boolean restoreFailed;
	private boolean presentingFailure;
	private boolean closed;
	private int page;
	private Future<?> work;

	public PreservationVaultScreen(Screen parent, ClientStorage storage, String modpackId, String modpackName, boolean activePack, Runnable closedCallback) {
		super(VersionedText.translatable("automodpack.vault.title"));
		this.parent = parent;
		this.storage = storage;
		this.modpackId = modpackId;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.activePack = activePack;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		if (!loading && snapshot == null) load();
		List<PreservationVault.Claim> claims = claims();
		int pageSize = rowsPerPage();
		int pageCount = Math.max(1, (claims.size() + pageSize - 1) / pageSize);
		page = Math.max(0, Math.min(pageCount - 1, page));
		int start = page * pageSize;
		int width = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		for (int index = start; index < Math.min(claims.size(), start + pageSize); index++) {
			PreservationVault.Claim claim = claims.get(index);
			int y = 66 + (index - start) * ROW_HEIGHT;
			String prefix = claim.claimId().equals(selectedClaimId) ? "[x] " : "[ ] ";
			String label = prefix + claim.originalPath() + "  " + UiFormat.formatSize(claim.size());
			Button select = buttonWidget(x, y, width, 20, VersionedText.literal(truncateToWidth(this.font, label, width - 12)), press -> select(claim));
			select.active = !busy && !loading;
			this.addRenderableWidget(select);
		}

		PreservationVault.Claim selected = selected();
		int actionY = this.height - 52;
		int actionWidth = actionButtonWidth(PANEL_WIDTH, 3);
		Button restore = buttonWidget(actionButtonX(PANEL_WIDTH, 3, 0), actionY, actionWidth, 20, VersionedText.translatable("automodpack.vault.restore"), press -> restore());
		restore.active = !busy && selected != null && activePack && selected.sourceRoot() == UpdatePlan.Root.GAME_DIR;
		this.addRenderableWidget(restore);
		Button copy = buttonWidget(actionButtonX(PANEL_WIDTH, 3, 1), actionY, actionWidth, 20, VersionedText.translatable("automodpack.vault.saveCopy"), press -> saveCopy());
		copy.active = !busy && selected != null;
		this.addRenderableWidget(copy);
		String deleteKey = selected != null && selected.claimId().equals(pendingDeleteClaimId) ? "automodpack.vault.confirmDelete" : "automodpack.vault.delete";
		Button delete = buttonWidget(actionButtonX(PANEL_WIDTH, 3, 2), actionY, actionWidth, 20, VersionedText.translatable(deleteKey), press -> delete());
		delete.active = !busy && selected != null;
		this.addRenderableWidget(delete);

		int footerY = this.height - 28;
		if (pageCount > 1) {
			int footerWidth = actionButtonWidth(PANEL_WIDTH, 3);
			Button previous = buttonWidget(actionButtonX(PANEL_WIDTH, 3, 0), footerY, footerWidth, 20, VersionedText.translatable("automodpack.ui.previous"), press -> changePage(-1));
			previous.active = !busy && page > 0;
			this.addRenderableWidget(previous);
			this.addRenderableWidget(buttonWidget(actionButtonX(PANEL_WIDTH, 3, 1), footerY, footerWidth, 20, VersionedText.translatable("automodpack.back"), press -> back()));
			Button next = buttonWidget(actionButtonX(PANEL_WIDTH, 3, 2), footerY, footerWidth, 20, VersionedText.translatable("automodpack.ui.next"), press -> changePage(1));
			next.active = !busy && page + 1 < pageCount;
			this.addRenderableWidget(next);
		} else {
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 1, 1, 0), footerY, actionButtonWidth(PANEL_WIDTH, 1), 20,
					VersionedText.translatable("automodpack.back"), press -> back()));
		}
	}

	private void load() {
		loading = true;
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				PreservationVault.Snapshot loaded = PreservationVault.read(storage, modpackId);
				this.minecraft.execute(() -> loaded(loaded));
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void loaded(PreservationVault.Snapshot loaded) {
		if (closed) return;
		snapshot = loaded;
		loading = false;
		busy = false;
		if (selectedClaimId != null && loaded.claims().stream().noneMatch(claim -> claim.claimId().equals(selectedClaimId))) selectedClaimId = null;
		pendingDeleteClaimId = null;
		rebuild();
	}

	private List<PreservationVault.Claim> claims() {
		if (snapshot == null) return List.of();
		return snapshot.claims().stream().sorted(Comparator.comparing(PreservationVault.Claim::preservedAt).reversed().thenComparing(PreservationVault.Claim::originalPath)).toList();
	}

	private PreservationVault.Claim selected() {
		if (selectedClaimId == null) return null;
		return claims().stream().filter(claim -> claim.claimId().equals(selectedClaimId)).findFirst().orElse(null);
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - 128) / ROW_HEIGHT);
	}

	private void select(PreservationVault.Claim claim) {
		selectedClaimId = claim.claimId();
		pendingDeleteClaimId = null;
		restoreFailed = false;
		rebuild();
	}

	private void changePage(int amount) {
		page = Math.max(0, page + amount);
		selectedClaimId = null;
		pendingDeleteClaimId = null;
		rebuild();
	}

	private void restore() {
		PreservationVault.Claim claim = selected();
		if (claim == null || busy || !activePack || claim.sourceRoot() != UpdatePlan.Root.GAME_DIR) return;
		run(() -> PreservationVault.restoreOriginal(storage, modpackId, claim.claimId()), true);
	}

	private void saveCopy() {
		PreservationVault.Claim claim = selected();
		if (claim == null || busy) return;
		run(() -> PreservationVault.saveCopy(storage, modpackId, claim.claimId()), false);
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
			PreservationVault.delete(storage, modpackId, claim.claimId());
			return null;
		}, false);
	}

	private void run(VaultOperation operation, boolean restoreAttempt) {
		busy = true;
		rebuild();
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				Path ignored = operation.run();
				PreservationVault.Snapshot refreshed = PreservationVault.read(storage, modpackId);
				this.minecraft.execute(() -> loaded(refreshed));
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

	private void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
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
		String title = VersionedText.translatable("automodpack.vault.titleNamed", modpackName).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		String description = loading ? VersionedText.translatable("automodpack.vault.loading").getString()
				: VersionedText.translatable(activePack ? "automodpack.vault.active" : "automodpack.vault.inactive", claims().size()).getString();
		List<String> descriptionLines = wrapToWidth(this.font, description, this.width - 28, 2);
		for (int index = 0; index < descriptionLines.size(); index++)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(descriptionLines.get(index)).withStyle(ChatFormatting.GRAY), this.width / 2, 28 + index * 12, TextColors.WHITE);
		if (restoreFailed) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.vault.restoreUnavailable").withStyle(ChatFormatting.AQUA), this.width / 2, 52, TextColors.WHITE);
		else if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.vault.working").withStyle(ChatFormatting.YELLOW), this.width / 2, 52, TextColors.WHITE);
		List<PreservationVault.Claim> claims = claims();
		int start = page * rowsPerPage();
		for (int index = start; index < Math.min(claims.size(), start + rowsPerPage()); index++) {
			PreservationVault.Claim claim = claims.get(index);
			int y = 88 + (index - start) * ROW_HEIGHT;
			String metadata = reason(claim.reason()) + "  |  " + status(claim.status());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, metadata, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(UiFormat.formatInstant(claim.preservedAt())).withStyle(ChatFormatting.GRAY), this.width / 2, y + 12, TextColors.WHITE);
		}
		if (!loading && claims.isEmpty()) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.vault.empty").withStyle(ChatFormatting.GRAY), this.width / 2, 88, TextColors.WHITE);
	}

	private static String reason(PreservationVault.Reason reason) {
		return VersionedText.translatable("automodpack.vault.reason." + reason.name().toLowerCase(Locale.ROOT)).getString();
	}

	private static String status(PreservationVault.Status status) {
		return VersionedText.translatable("automodpack.vault.status." + status.name().toLowerCase(Locale.ROOT)).getString();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}

	@FunctionalInterface
	private interface VaultOperation {
		Path run() throws Exception;
	}
}
