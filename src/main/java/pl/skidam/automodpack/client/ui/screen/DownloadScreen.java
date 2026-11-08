package pl.skidam.automodpack.client.ui.screen;

import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedIconButton;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack_core.client.WaitingMusic;
import pl.skidam.automodpack_core.screen.DownloadView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.WorkStatus;

public class DownloadScreen extends VersionedScreen {
	private static final Logger LOGGER = LogManager.getLogger(DownloadScreen.class);

	private static final Identifier PROGRESS_BAR_EMPTY_TEXTURE = Common.id("textures/gui/sprites/progress_background.png");
	private static final Identifier PROGRESS_BAR_FULL_TEXTURE = Common.id("textures/gui/sprites/green_progress.png");
	private static final int PROGRESS_BAR_WIDTH = 182;
	private static final int PROGRESS_BAR_HEIGHT = 5;

	private DownloadView download;
	private String header;
	private Runnable onCancel;

	private boolean musicStarted = false;
	private WorkStatus.Phase visiblePhase = WorkStatus.Phase.READING;
	private long visibleSince = System.currentTimeMillis();
	private long lastPhasePoll;
	private AbstractWidget cancelButton;
	private Button musicButton;

	private String cachedStage = "0/0";
	private double cachedPercentage = 0.0;
	private String cachedSpeed = "0 B/s";
	private String cachedETA = "Calculating...";

	private long lastTextUpdate = 0;
	private static final long TEXT_UPDATE_INTERVAL = 100; // Update strings 10x per second

	public DownloadScreen(DownloadView download, String header, Runnable onCancel) {
		super(VersionedText.text("automodpack.download.title"));
		this.download = download;
		this.header = header;
		this.onCancel = onCancel;
	}

	@Override
	protected void init() {
		super.init();
		initWidgets();
	}

	/** Hands the still-visible wait screen the live download, so the staged view and the waiting music never restart. */
	public void attach(DownloadView download, String header, Runnable onCancel) {
		this.download = download;
		this.header = header;
		this.onCancel = onCancel;
		if (cancelButton != null) cancelButton.active = true;
	}

	private void initWidgets() {
		cancelButton = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.text("automodpack.cancel"), button -> {
					cancelButton.active = false;
					cancelDownload();
					AudioManager.stopMusic();
				}))).get(0);

		// The music toggle is one square icon button at the right end of the footer rail, the vanilla icon-button idiom.
		// No label on the button: the icon speaks and the tooltip names it - a text label inside a 20px button scissor-clips mid-word.
		int iconX = panelLeft(ActionAreaLayout.FOOTER_RAIL) + panelWidth(ActionAreaLayout.FOOTER_RAIL) - ActionAreaLayout.BUTTON_HEIGHT;
		int iconY = VersionedScreen.widgetY(cancelButton);
		musicButton = addRenderableWidget(VersionedScreen.iconButtonWidget(iconX, iconY, ActionAreaLayout.BUTTON_HEIGHT, 16, button -> toggleMusic(), "music-note", VersionedText.text("automodpack.music.play")));
	}

	/** Unlike the details screen's preview, this choice is the player's real one and is remembered for the next download. */
	private void toggleMusic() {
		if (AudioManager.isMusicPlaying()) {
			AudioManager.stopMusic();
			ClientPreferences.setMusicEnabled(false);
		} else {
			AudioManager.playMusic();
			ClientPreferences.setMusicEnabled(true);
		}
	}

	private void updateUIState() {
		if (download == null || !download.isRunning()) return;

		long now = System.currentTimeMillis();
		if (now - lastTextUpdate >= TEXT_UPDATE_INTERVAL) {
			lastTextUpdate = now;

			cachedStage = download.getStage();
			cachedPercentage = download.getPrecisePercentage();
			cachedSpeed = ByteFormat.formatSpeed(download.getDownloadSpeed());
			cachedETA = ByteFormat.formatETA(download.getETA());
		}
	}

	private Component getStage() {
		return VersionedText.literal(cachedStage);
	}

	private Component getTotalDownloadSpeed() {
		return "-1".equals(cachedSpeed) ? VersionedText.text("automodpack.download.calculating") : VersionedText.literal(cachedSpeed);
	}

	private Component getTotalETA() {
		return "-1".equals(cachedETA) ? null : VersionedText.text("automodpack.download.eta", cachedETA);
	}

	private Component getFailedSummary() {
		return download.failed() > 0 ? VersionedText.text("automodpack.download.failedCount", download.failed()) : null;
	}

	private float getDownloadScale() {
		return (float) (Math.max(0.0, Math.min(100.0, cachedPercentage)) * 0.01);
	}

	private boolean downloadsInProgress() {
		return download != null && !download.downloadingFileNames().isEmpty();
	}

	private void drawDownloadingFiles(VersionedMatrices matrices) {
		int y = this.height / 2 - 94;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.download.downloading").withStyle(ChatFormatting.BOLD), this.width / 2, y, TextColors.WHITE);
		List<String> names = download.downloadingFileNames();
		int shown = Math.min(3, names.size());
		int currentY = y + 14;
		for (int index = 0; index < shown; index++) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, names.get(index), Math.max(1, panelWidth(310) - 20))), this.width / 2, currentY,
					TextColors.WHITE);
			currentY += 10;
		}
		if (names.size() > shown)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.download.moreFiles", names.size() - shown).withStyle(ChatFormatting.GRAY), this.width / 2, currentY,
					TextColors.WHITE);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		updateUIState();

		if (download != null && download.isRunning()) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, header, panelWidth(310))).withStyle(ChatFormatting.BOLD), this.width / 2, this.height / 2 - 110,
					TextColors.WHITE);
			if (downloadsInProgress()) drawDownloadingFiles(matrices);
			boolean complete = cachedPercentage >= 99.9;
			if (complete) {
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.download.saving").withStyle(ChatFormatting.GRAY), this.width / 2, this.height / 2 - 20, TextColors.WHITE);
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.download.savingDetail").withStyle(ChatFormatting.DARK_GRAY), this.width / 2, this.height / 2 - 4, TextColors.WHITE);
			} else {
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.download.files", getStage().getString()).append(" · " + (int) cachedPercentage + "%"), this.width / 2,
						this.height / 2 - 20, TextColors.WHITE);
				Component eta = getTotalETA();
				if (eta != null) drawCenteredTextWithShadow(matrices, this.font, (MutableComponent) eta, this.width / 2, this.height / 2 - 4, TextColors.WHITE);
			}

			float scaleBar = 1.35F;
			int barFilledWidth = (int) (PROGRESS_BAR_WIDTH * getDownloadScale());
			int barY = this.height / 2 + 8;
			float barDrawX = (this.width - PROGRESS_BAR_WIDTH * scaleBar) / 2.0F / scaleBar;
			float barDrawY = barY / scaleBar;

			matrices.pushPose();
			matrices.scale(scaleBar, scaleBar, scaleBar);
			drawTexture(PROGRESS_BAR_EMPTY_TEXTURE, matrices, Math.round(barDrawX), Math.round(barDrawY), 0, 0, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH,
					PROGRESS_BAR_HEIGHT);
			drawTexture(PROGRESS_BAR_FULL_TEXTURE, matrices, Math.round(barDrawX), Math.round(barDrawY), 0, 0, Math.min(barFilledWidth, PROGRESS_BAR_WIDTH), PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH,
					PROGRESS_BAR_HEIGHT);
			matrices.popPose();

			if (!complete) drawCenteredTextWithShadow(matrices, this.font, (MutableComponent) getTotalDownloadSpeed(), this.width / 2, this.height / 2 + 24, TextColors.WHITE);
			Component failed = getFailedSummary();
			if (failed != null) drawCenteredTextWithShadow(matrices, this.font, (MutableComponent) failed, this.width / 2, this.height / 2 + 36, TextColors.GRAY);
			cancelButton.active = true;
			cancelButton.visible = !complete;
		} else {
			drawWorkStatus(matrices);
			cancelButton.active = download == null || !download.isCancelled();
			cancelButton.visible = cancellablePhase();
		}

		checkAndStartMusic();
		updateMusicButton();
	}

	private void drawWorkStatus(VersionedMatrices matrices) {
		updateVisiblePhase();
		int center = this.width / 2;
		int titleY = this.height / 2 - 34;
		if (!header.isBlank())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, header, panelWidth(310))).withStyle(ChatFormatting.BOLD), center, titleY - 14,
					TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.progress.title").withStyle(ChatFormatting.BOLD), center, titleY, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, phaseLabel(visiblePhase).withStyle(ChatFormatting.GRAY), center, titleY + 16, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.progress.detail." + visiblePhase.name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY), center, titleY + 27,
				TextColors.WHITE);
		long total = WorkStatus.total();
		long completed = WorkStatus.completed();
		int barX = center - PROGRESS_BAR_WIDTH / 2;
		int barY = titleY + 54;
		drawTexture(PROGRESS_BAR_EMPTY_TEXTURE, matrices, barX, barY, 0, 0, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
		if (total > 0) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.progress.count", Math.min(completed, total), total).withStyle(ChatFormatting.GRAY), center, titleY + 40,
					TextColors.WHITE);
			int filled = (int) Math.min(PROGRESS_BAR_WIDTH, Math.max(0, PROGRESS_BAR_WIDTH * completed / total));
			drawTexture(PROGRESS_BAR_FULL_TEXTURE, matrices, barX, barY, 0, 0, filled, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
		} else {
			// Indeterminate work gets a slow moving segment, never a made-up percentage.
			int segment = 46;
			int travel = Math.max(0, PROGRESS_BAR_WIDTH - segment);
			long cycle = Math.max(1, travel * 2L);
			long position = (System.currentTimeMillis() / 8) % cycle;
			int segmentX = barX + (int) (position <= travel ? position : cycle - position);
			drawTexture(PROGRESS_BAR_FULL_TEXTURE, matrices, segmentX, barY, 0, 0, segment, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
		}
	}

	private MutableComponent phaseLabel(WorkStatus.Phase phase) {
		return VersionedText.text("automodpack.progress." + phase.name().toLowerCase(Locale.ROOT));
	}

	/** The engine's phase can change faster than a player reads it; hold each label long enough to be seen. */
	private void updateVisiblePhase() {
		long now = System.currentTimeMillis();
		if (now - lastPhasePoll < 250) return;
		lastPhasePoll = now;
		WorkStatus.Phase next = WorkStatus.phase();
		if (next == visiblePhase || now - visibleSince < 350) return;
		visiblePhase = next;
		visibleSince = now;
	}

	private boolean cancellablePhase() {
		if (download != null && download.isRunning()) return true;
		return visiblePhase != WorkStatus.Phase.APPLYING && visiblePhase != WorkStatus.Phase.FINISHING;
	}

	/** The icon shows the action, so a download that autoplayed music rests on the red X and one that did not rests on the note. */
	private void updateMusicButton() {
		boolean playing = AudioManager.isMusicPlaying();
		Component action = VersionedText.text(playing ? "automodpack.music.stop" : "automodpack.music.play");
		((VersionedIconButton) musicButton).setSprite(playing ? "mute-music-note" : "music-note");
		((VersionedIconButton) musicButton).setNarration(action);
		setTooltip(musicButton, action);
	}

	private void checkAndStartMusic() {
		musicButton.active = true;
		// The pre-transfer wait screen and the transfer screen are the same class; only the screen that owns a
		// running download starts the track, so handing over between them never tears the audio device down.
		if (musicStarted || download == null) return;
		if (ClientStorage.current().clientConfig().current().playMusic) AudioManager.playMusic();
		musicStarted = true;
	}

	@Override
	public void removed() {
		AudioManager.stopMusic();
		WaitingMusic.endRun();
		super.removed();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return cancelButton != null && cancelButton.active && cancelButton.visible;
	}

	@Override
	public void onClose() {
		if (cancelButton != null && cancelButton.active && cancelButton.visible) {
			cancelButton.active = false;
			cancelDownload();
			AudioManager.stopMusic();
		}
	}

	public void cancelDownload() {
		try {
			if (onCancel != null) onCancel.run();
			if (download != null && !download.isCancelled()) download.cancelAllAndShutdown();
		} catch (Exception e) {
			LOGGER.error("Failed to cancel the download run", e);
		}
	}
}
