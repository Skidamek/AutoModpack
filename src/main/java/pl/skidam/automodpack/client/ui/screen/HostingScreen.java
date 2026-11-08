package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.Hosting;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.host.HostSource;
import pl.skidam.automodpack_core.modpack.host.HostingDesk;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** What this instance hosts to players joining its worlds; never an installed pack, so it lives on its own card. */
public final class HostingScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = ActionAreaLayout.FOOTER_RAIL;
	private static final int SHORT_TOKEN = 12;

	private final Screen parent;
	private HostingDesk.Overview overview;
	private String outcomeLine;
	private boolean busy = true;
	private boolean closed;

	public HostingScreen(Screen parent) {
		super(VersionedText.text("automodpack.hosting.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		closed = false;
		if (overview == null && busy) ScreenManager.background(this::load);
		boolean generates = overview != null && overview.nextSource() instanceof HostSource.Generated && !busy;
		MutableComponent generate = VersionedText.text("automodpack.hosting.generate");
		MutableComponent preview = VersionedText.text("automodpack.hosting.preview");
		ActionRow actions = actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				generates ? primaryAction(generate, press -> run(false)) : disabledAction(generate),
				generates ? optionalAction(preview, press -> run(true)) : disabledAction(preview),
				overview != null && overview.pinSuffix() != null
						? optionalAction(VersionedText.text("automodpack.hosting.copyPin"), press -> Minecraft.getInstance().keyboardHandler.setClipboard(overview.pinSuffix()))
						: disabledAction(VersionedText.text("automodpack.hosting.copyPin")));
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
		int actionsTop = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actions, footer);
		addCenteredScrollBody(panelWidth(PANEL_WIDTH), 32, actionsTop - 8, lines());
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actions, footer);
	}

	private List<MutableComponent> lines() {
		List<MutableComponent> lines = new ArrayList<>(wrapParagraph(this.font, VersionedText.str("automodpack.hosting.description"), panelWidth(PANEL_WIDTH), ChatFormatting.GRAY));
		lines.add(blankLine());
		if (overview == null) {
			lines.add(VersionedText.text("automodpack.hosting.loading").withStyle(ChatFormatting.GRAY));
			return lines;
		}
		lines.add(nextLine(overview.nextSource()).withStyle(ChatFormatting.YELLOW));
		lines.add(blankLine());
		if (overview.generated().isPresent()) {
			HostingDesk.Generated generated = overview.generated().get();
			String name = generated.name().isBlank() ? generated.modpackId() : generated.name();
			lines.add(VersionedText.text("automodpack.hosting.generated", name, generated.modpackId()).withStyle(ChatFormatting.WHITE));
			lines.add(VersionedText.text("automodpack.hosting.content", shortToken(generated.contentToken())).withStyle(ChatFormatting.GRAY));
			lines.add(VersionedText.text("automodpack.hosting.historyTitle").withStyle(ChatFormatting.WHITE));
			List<JournalEntry> history = overview.history();
			for (int index = history.size() - 1; index >= 0; index--) {
				JournalEntry entry = history.get(index);
				lines.add(VersionedText.text("automodpack.hosting.historyEntry", entry.seq(), entry.createdAt().toString()).withStyle(ChatFormatting.GRAY));
			}
		} else {
			lines.add(VersionedText.text("automodpack.hosting.notGenerated").withStyle(ChatFormatting.GRAY));
		}
		lines.add(blankLine());
		if (overview.fingerprint() == null) {
			lines.add(VersionedText.text("automodpack.hosting.noFingerprint").withStyle(ChatFormatting.GRAY));
		} else {
			lines.add(VersionedText.text("automodpack.hosting.fingerprint", overview.fingerprint()).withStyle(ChatFormatting.WHITE));
			for (MutableComponent line : wrapParagraph(this.font, VersionedText.str("automodpack.hosting.pinHint", overview.pinSuffix()), panelWidth(PANEL_WIDTH), ChatFormatting.GRAY))
				lines.add(line);
		}
		for (MutableComponent line : wrapParagraph(this.font, VersionedText.str("automodpack.hosting.live"), panelWidth(PANEL_WIDTH), ChatFormatting.GRAY)) lines.add(line);
		if (outcomeLine != null) {
			lines.add(blankLine());
			for (MutableComponent line : wrapParagraph(this.font, outcomeLine, panelWidth(PANEL_WIDTH), ChatFormatting.GREEN)) lines.add(line);
		}
		return lines;
	}

	private static MutableComponent nextLine(HostSource source) {
		if (source instanceof HostSource.Relay relay) return VersionedText.text("automodpack.hosting.nextRelay", relay.modpackId());
		if (source instanceof HostSource.None none) return VersionedText.text("automodpack.hosting.nextNone", none.absence().detail());
		return VersionedText.text("automodpack.hosting.nextGenerated");
	}

	private void load() {
		try {
			HostingDesk.Overview loaded = HostingDesk.read(GameDirectory.current());
			this.minecraft.execute(() -> loaded(loaded));
		} catch (Exception e) {
			this.minecraft.execute(() -> fail(e));
		}
	}

	private void loaded(HostingDesk.Overview loaded) {
		if (closed) return;
		overview = loaded;
		busy = false;
		rebuild();
	}

	private void run(boolean preview) {
		busy = true;
		rebuild();
		ScreenManager.background(() -> {
			try {
				HostingDesk.Outcome outcome = HostingDesk.generate(GameDirectory.current(), preview, Hosting.executorOrNull() != null);
				HostingDesk.Overview refreshed = HostingDesk.read(GameDirectory.current());
				this.minecraft.execute(() -> {
					outcomeLine = describe(outcome);
					loaded(refreshed);
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private static String describe(HostingDesk.Outcome outcome) {
		String token = outcome.contentToken() == null ? "" : shortToken(outcome.contentToken());
		String line = switch (outcome.kind()) {
			case PUBLISHED -> VersionedText.str("automodpack.hosting.published", outcome.files(), token);
			case NO_CHANGES -> VersionedText.str("automodpack.hosting.noChanges", token);
			case PREVIEW -> VersionedText.str("automodpack.hosting.previewReady", outcome.files(), token);
			case NOTHING, REFUSED -> VersionedText.str("automodpack.hosting.failed", outcome.detail());
		};
		return outcome.kind() == HostingDesk.OutcomeKind.PREVIEW && !outcome.detail().isBlank() ? line + " " + outcome.detail() : line;
	}

	private static String shortToken(String token) {
		return token.length() <= SHORT_TOKEN ? token : token.substring(0, SHORT_TOKEN);
	}

	private void fail(Exception exception) {
		if (closed) return;
		busy = false;
		ScreenManager.failure(FailureRequest.of(exception, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.hosting.title").withStyle(ChatFormatting.BOLD), this.width / 2, 16, TextColors.WHITE);
	}

	@Override
	public void removed() {
		closed = true;
		super.removed();
	}

	@Override
	public void onClose() {
		ScreenImpl.setScreen(parent);
	}
}
