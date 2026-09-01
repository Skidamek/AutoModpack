package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.clientConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.WaitingPresentation;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;

public class DownloadScreen extends VersionedScreen {
	private static final Identifier PROGRESS_BAR_EMPTY_TEXTURE = Common.id("textures/gui/sprites/green_background.png");
	private static final Identifier PROGRESS_BAR_FULL_TEXTURE = Common.id("textures/gui/sprites/green_progress.png");
	private static final int PROGRESS_BAR_WIDTH = 182;
	private static final int PROGRESS_BAR_HEIGHT = 5;

	private final DownloadManager downloadManager;
	private final String header;

	private final long startedAtNanos = System.nanoTime();
	private long ticks = 0;
	private boolean musicStarted = false;
	private Button cancelButton;
	private Button muteMusicButton;
	private Button playMusicButton;

	// UI Cache
	private String cachedStage = "0/0";
	private double cachedPercentage = 0.0;
	private String cachedSpeed = "0 B/s";
	private String cachedETA = "Calculating...";

	private long lastTextUpdate = 0;
	private static final long TEXT_UPDATE_INTERVAL = 100; // Update strings 10x per second

	public DownloadScreen(DownloadManager downloadManager, String header) {
		super(VersionedText.translatable("automodpack.download.title"));
		this.downloadManager = downloadManager;
		this.header = header;
	}

	@Override
	protected void init() {
		super.init();
		initWidgets();
	}

	private void initWidgets() {
		cancelButton = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height / 2 + 56, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.cancel"), button -> {
					cancelButton.active = false;
					cancelDownload();
					AudioManager.stopMusic();
				}))).get(0);

		/*? if >= 1.19.4 {*/
		int x = cancelButton.getX() + cancelButton.getWidth() + ActionAreaLayout.GAP;
		int y = cancelButton.getY();
		/*?} else {*/
		/*int x = cancelButton.x + cancelButton.getWidth() + ActionAreaLayout.GAP;
		int y = cancelButton.y;
		*//*?}*/

		muteMusicButton = addRenderableWidget(VersionedScreen.iconButtonWidget(x, y, 20, 8, button -> {
			AudioManager.stopMusic();
			ClientPreferences.setMusicEnabled(false);
		}, "music-note", VersionedText.translatable("soundCategory.music")));
		setTooltip(muteMusicButton, VersionedText.translatable("soundCategory.music"));

		playMusicButton = addRenderableWidget(VersionedScreen.iconButtonWidget(x, y, 20, 8, button -> {
			AudioManager.playMusic();
			ClientPreferences.setMusicEnabled(true);
		}, "mute-music-note", VersionedText.translatable("soundCategory.music")));
		setTooltip(playMusicButton, VersionedText.translatable("soundCategory.music"));
	}

	private void updateUIState() {
		if (downloadManager == null || !downloadManager.isRunning()) return;

		long now = System.currentTimeMillis();
		if (now - lastTextUpdate >= TEXT_UPDATE_INTERVAL) {
			lastTextUpdate = now;

			cachedStage = downloadManager.getStage();
			cachedPercentage = downloadManager.getPrecisePercentage();
			cachedSpeed = ByteFormat.formatSpeed(downloadManager.getDownloadSpeed());
			cachedETA = ByteFormat.formatETA(downloadManager.getETA());
		}
	}

	// --- Components ---

	private Component getStage() {
		return VersionedText.literal(cachedStage);
	}

	private Component getPercentage() {
		return VersionedText.literal((int) cachedPercentage + "%");
	}

	private Component getTotalDownloadSpeed() {
		return "-1".equals(cachedSpeed) ? VersionedText.translatable("automodpack.download.calculating") : VersionedText.literal(cachedSpeed);
	}

	private Component getTotalETA() {
		return "-1".equals(cachedETA)
				? VersionedText.translatable("automodpack.download.calculating")
				: VersionedText.translatable("automodpack.download.eta", cachedETA);
	}

	private Component getAcquisitionSummary() {
		int acquired = 0;
		int failed = 0;
		if (downloadManager != null) {
			for (DownloadManager.AcquisitionResult result : downloadManager.getAcquisitionResults().values()) {
				if (result.success()) acquired++;
				else failed++;
			}
		}
		return VersionedText.translatable("automodpack.download.acquired", acquired, failed);
	}

	private float getDownloadScale() {
		return (float) (Math.max(0.0, Math.min(100.0, cachedPercentage)) * 0.01);
	}

	private boolean downloadsInProgress() {
		return downloadManager != null && !downloadManager.downloadsInProgress.isEmpty();
	}

	private void drawDownloadingFiles(VersionedMatrices matrices) {
		int y = this.height / 2 - 94;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.download.downloading").withStyle(ChatFormatting.BOLD), this.width / 2, y, TextColors.WHITE);
		int currentY = y + 14;
		synchronized (downloadManager.downloadsInProgress) {
			for (DownloadManager.DownloadData data : downloadManager.downloadsInProgress.values()) {
				String fileName = truncateToWidth(this.font, data.getFileName(), Math.max(1, panelWidth(310) - 20));
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(fileName), this.width / 2, currentY, TextColors.GRAY);
				currentY += 10;
			}
		}
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		updateUIState();

		if (downloadManager != null && downloadManager.isRunning()) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, header, panelWidth(310))).withStyle(ChatFormatting.BOLD), this.width / 2, this.height / 2 - 110, TextColors.WHITE);
			if (downloadsInProgress()) drawDownloadingFiles(matrices);
			drawCenteredTextWithShadow(matrices, this.font, (MutableComponent) getStage(), this.width / 2, this.height / 2 - 20, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, (MutableComponent) getTotalETA(), this.width / 2, this.height / 2 - 4, TextColors.WHITE);

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

			drawCenteredTextWithShadow(matrices, this.font, (MutableComponent) getTotalDownloadSpeed(), this.width / 2, this.height / 2 + 24, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, (MutableComponent) getAcquisitionSummary(), this.width / 2, this.height / 2 + 36, TextColors.GRAY);
			cancelButton.active = true;
		} else {
			WaitingPresentation.render(matrices, this.font, this.width, this.height, System.nanoTime() - startedAtNanos);
			cancelButton.active = downloadManager == null || !downloadManager.isCancelled();
		}

		checkAndStartMusic();
		updateMusicButtons();
	}

	private void updateMusicButtons() {
		if (playMusicButton.active && muteMusicButton.active) {
			boolean playing = AudioManager.isMusicPlaying();
			muteMusicButton.visible = playing;
			playMusicButton.visible = !playing;
		} else {
			muteMusicButton.visible = clientConfig.playMusic;
			playMusicButton.visible = !clientConfig.playMusic;
		}
	}

	private void checkAndStartMusic() {
		if (ticks++ <= 30) {
			muteMusicButton.active = false;
			playMusicButton.active = false;
			return;
		}
		muteMusicButton.active = true;
		playMusicButton.active = true;

		if (musicStarted) return;
		if (clientConfig.playMusic) AudioManager.playMusic();
		musicStarted = true;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> {
			if (cancelButton != null && cancelButton.active) {
				cancelButton.active = false;
				cancelDownload();
				AudioManager.stopMusic();
			}
		});
	}

	public void cancelDownload() {
		try {
			if (downloadManager != null) downloadManager.cancelAllAndShutdown();
			ScreenImpl.multiplayer();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
