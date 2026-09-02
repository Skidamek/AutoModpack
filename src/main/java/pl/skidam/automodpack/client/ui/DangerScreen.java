package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;

public class DangerScreen extends VersionedScreen {

	private final ModpackUpdater modpackUpdater;
	private boolean confirmationFinished;

	public DangerScreen(ModpackUpdater modpackUpdater) {
		super(VersionedText.literal("DangerScreen"));
		this.modpackUpdater = modpackUpdater;

		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	@Override
	protected void init() {
		super.init();

		this.addRenderableWidget(buttonWidget(this.width / 2 - 115, this.height - 48, 120, 20, VersionedText.translatable("automodpack.danger.cancel"),
				button -> cancelConfirmation()));

		this.addRenderableWidget(buttonWidget(this.width / 2 + 15, this.height - 48, 120, 20,
				VersionedText.translatable("automodpack.danger.confirm").withStyle(ChatFormatting.BOLD), button -> startUpdate()));
	}

	private void startUpdate() {
		modpackUpdater.startConfirmedUpdate();
	}

	private void cancelConfirmation() {
		modpackUpdater.cancelConfirmation();
		ScreenImpl.multiplayer();
	}

	@Override
	public void tick() {
		super.tick();
		if (confirmationFinished) return;
		ModpackUpdater.ConfirmationState state = modpackUpdater.getConfirmationState();
		if (state != ModpackUpdater.ConfirmationState.EXPIRED && state != ModpackUpdater.ConfirmationState.CANCELLED) return;
		confirmationFinished = true;
		ScreenImpl.multiplayer();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int lineHeight = 12; // Consistent line spacing

		// Title
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.danger").withStyle(ChatFormatting.BOLD), this.width / 2,
				this.height / 2 - 60, TextColors.WHITE);

		// Description line 1
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.danger.description"), this.width / 2,
				this.height / 2 - 60 + lineHeight * 3, TextColors.WHITE);

		// Description line 2
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.danger.secDescription"), this.width / 2,
				this.height / 2 - 60 + lineHeight * 4, TextColors.WHITE);

		// Description line 3
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.danger.thiDescription"), this.width / 2,
				this.height / 2 - 60 + lineHeight * 5, TextColors.WHITE);
	}

	@Override
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 257) { // Enter key (GLFW_KEY_ENTER = 257)
			startUpdate();
			return true;
		}
		return super.onKeyPress(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
