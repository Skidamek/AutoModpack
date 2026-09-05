package pl.skidam.automodpack.client.ui.screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;

public class ErrorScreen extends VersionedScreen {

	private final Screen parent;
	private final FailureRequest request;
	private Button backButton;
	private Button logsButton;
	private Button copyButton;
	private Button retryButton;
	private boolean copied;
	private int categoryY;

	public ErrorScreen(Screen parent, FailureRequest request) {
		super(VersionedText.translatable("automodpack.error.title"));
		this.parent = parent;
		this.request = request;

		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	@Override
	protected void init() {
		super.init();
		initWidgets();
	}

	private void initWidgets() {
		List<ActionRow> rows;
		if (request.retryAction() != null) {
			rows = List.of(
					actionRow(ActionAreaLayout.RowKind.AUXILIARY,
							primaryAction(VersionedText.translatable("automodpack.error.retry"), button -> retry()),
							optionalAction(VersionedText.translatable("automodpack.error.openLogs"), button -> openLogs())),
					actionRow(ActionAreaLayout.RowKind.FOOTER,
							optionalAction(VersionedText.translatable("automodpack.error.copyDetails"), button -> copyDetails()),
							secondaryAction(VersionedText.translatable("automodpack.back"), button -> back())));
		} else {
			rows = List.of(
					actionRow(ActionAreaLayout.RowKind.AUXILIARY,
							optionalAction(VersionedText.translatable("automodpack.error.openLogs"), button -> openLogs()),
							optionalAction(VersionedText.translatable("automodpack.error.copyDetails"), button -> copyDetails())),
					actionRow(ActionAreaLayout.RowKind.FOOTER,
							secondaryAction(VersionedText.translatable("automodpack.back"), button -> back())));
		}
		List<Button> buttons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows.toArray(ActionRow[]::new));
		if (request.retryAction() != null) {
			retryButton = buttons.get(0);
			logsButton = buttons.get(1);
			copyButton = buttons.get(2);
			backButton = buttons.get(3);
		} else {
			retryButton = null;
			logsButton = buttons.get(0);
			copyButton = buttons.get(1);
			backButton = buttons.get(2);
		}

		// Pinned header: title line at 36, the "copied" confirmation at 62 while it shows, the category right under it.
		categoryY = 62 + (copied ? 16 : 0);
		int footerTop = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows.toArray(ActionRow[]::new));
		int wrapWidth = Math.max(1, this.width - 30);
		List<MutableComponent> lines = new ArrayList<>();
		String summary = VersionedText.translatable(request.messageKey(), request.translationArguments()).getString();
		lines.addAll(wrapParagraph(this.font, summary, wrapWidth, ChatFormatting.GRAY));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.error.details").getString(), wrapWidth, ChatFormatting.GRAY));
		DialogColumn column = layoutDialogColumn(categoryY + 16, footerTop, lines.size() * LINE_HEIGHT, 0);
		this.addScrollBody(wrapWidth, column.bodyTop(), column.bodyBottom(), lines, true);
	}

	private void back() {
		ScreenImpl.setScreen(parent);
	}

	private void retry() {
		Runnable retryAction = request.retryAction();
		if (retryAction == null) return;
		ScreenImpl.setScreen(parent);
		retryAction.run();
	}

	private void openLogs() {
		Path gameDirectory = GameDirectory.current();
		Path logsDirectory = gameDirectory.resolve("logs");
		Util.getPlatform().openFile((Files.isDirectory(logsDirectory) ? logsDirectory : gameDirectory).toFile());
	}

	private void copyDetails() {
		Minecraft.getInstance().keyboardHandler.setClipboard(request.diagnosticText());
		copied = true;
		rebuild();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.translatable("automodpack.error.titleLine", VersionedText.translatable("automodpack.error").getString()).withStyle(ChatFormatting.RED),
				this.width / 2, 36, TextColors.WHITE);
		if (copied) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.error.copied").withStyle(ChatFormatting.GREEN), this.width / 2, 62, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.error.category", VersionedText.translatable(request.category().translationKey())).withStyle(ChatFormatting.GRAY),
				this.width / 2, categoryY, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}
}
