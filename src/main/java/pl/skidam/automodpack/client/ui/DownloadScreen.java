package pl.skidam.automodpack.client.ui;

import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.SpeedFormatter;

public class DownloadScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 460;

	private final DownloadManager downloadManager;
	private final String header;

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
		super(VersionedText.literal("DownloadScreen"));
		this.downloadManager = downloadManager;
		this.header = header;
	}

	@Override
	protected void init() {
		super.init();
		initWidgets();
	}

	private void saveClientConfig() {
		try {
			ConfigTools.writeAtomic(ClientStorage.fromGameDirectory(SmartFileUtils.CWD).clientConfigFile(), clientConfig);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save client configuration", e);
		}
	}

	private void initWidgets() {
		cancelButton = addRenderableWidget(
				buttonWidget(this.width / 2 - 60, this.height - 48, 120, 20, VersionedText.translatable("automodpack.cancel"), button -> {
					cancelButton.active = false;
					cancelDownload();
					AudioManager.stopMusic();
				}));

		int x = this.width - 28;
		int y = this.height - 28;

		muteMusicButton = addRenderableWidget(VersionedScreen.iconButtonWidget(x, y, 20, 8, button -> {
			AudioManager.stopMusic();
			clientConfig.playMusic = false;
			saveClientConfig();
		}, "music-note"));

		playMusicButton = addRenderableWidget(VersionedScreen.iconButtonWidget(x, y, 20, 8, button -> {
			AudioManager.playMusic();
			clientConfig.playMusic = true;
			saveClientConfig();
		}, "mute-music-note"));
	}

	private void updateUIState() {
		if (downloadManager == null || !downloadManager.isRunning()) return;

		long now = System.currentTimeMillis();
		if (now - lastTextUpdate >= TEXT_UPDATE_INTERVAL) {
			lastTextUpdate = now;

			cachedStage = downloadManager.getStage();
			cachedPercentage = downloadManager.getPrecisePercentage();
			cachedSpeed = SpeedFormatter.formatSpeed(downloadManager.getDownloadSpeed());
			cachedETA = SpeedFormatter.formatETA(downloadManager.getETA());
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
		return VersionedText.literal("Acquired: " + acquired + "  Failed: " + failed);
	}

	private float getDownloadScale() {
		return (float) (Math.max(0.0, Math.min(100.0, cachedPercentage)) * 0.01);
	}

	private void drawDownloadingFiles(VersionedMatrices matrices, int left, int maxWidth) {
		int y = this.height / 2 - 70;
		drawTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.download.downloading").withStyle(ChatFormatting.BOLD), left, y, TextColors.WHITE);
		int currentY = y + 16;
		if (downloadManager != null && !downloadManager.downloadsInProgress.isEmpty()) {
			synchronized (downloadManager.downloadsInProgress) {
				for (DownloadManager.DownloadData data : downloadManager.downloadsInProgress.values()) {
					drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, data.getFileName(), maxWidth)), left, currentY, TextColors.GRAY);
					currentY += 12;
				}
			}
		} else {
			drawTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.download.noFiles"), left, currentY, TextColors.GRAY);
		}
	}

	@Override
	public void versionedBackground(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawPanel(matrices, PANEL_WIDTH, this.height / 2 - 108, this.height - 64);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		updateUIState();
		int lineHeight = 12;
		int left = panelLeft(PANEL_WIDTH) + 20;
		int right = panelLeft(PANEL_WIDTH) + panelWidth(PANEL_WIDTH) - 20;
		int contentWidth = right - left;

		drawTextWithShadow(matrices, this.font, VersionedText.literal(header).withStyle(ChatFormatting.BOLD), left, this.height / 2 - 94, TextColors.WHITE);
		drawDownloadingFiles(matrices, left, contentWidth);

		if (downloadManager != null && downloadManager.isRunning()) {
			drawTextWithShadow(matrices, this.font, (MutableComponent) getStage(), left, this.height / 2 - 6, TextColors.WHITE);
			drawTextWithShadow(matrices, this.font, (MutableComponent) getTotalETA(), left, this.height / 2 + lineHeight + 2, TextColors.GRAY);
			drawTextWithShadow(matrices, this.font, (MutableComponent) getPercentage(), right - this.font.width(getPercentage()), this.height / 2 - 6, TextColors.PANEL_ACCENT);

			int barY = this.height / 2 + 30;
			int barFilledWidth = (int) (contentWidth * getDownloadScale());
			matrices.fill(left, barY, right, barY + 7, TextColors.PANEL_DIVIDER);
			matrices.fill(left, barY, left + Math.min(contentWidth, barFilledWidth), barY + 7, TextColors.PANEL_ACCENT);

			drawTextWithShadow(matrices, this.font, (MutableComponent) getTotalDownloadSpeed(), left, barY + 16, TextColors.WHITE);
			drawTextWithShadow(matrices, this.font, (MutableComponent) getAcquisitionSummary(), left, barY + 30, TextColors.GRAY);
			cancelButton.active = true;
		} else {
			cancelButton.active = false;
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
		return false;
	}

	public void cancelDownload() {
		try {
			if (downloadManager != null) downloadManager.cancelAllAndShutdown();
			new ScreenManager().title();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
