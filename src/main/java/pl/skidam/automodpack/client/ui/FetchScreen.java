package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class FetchScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 400;

	private Button cancelButton;
	private final FetchManager fetchManager;

	public FetchScreen(FetchManager fetchManager) {
		super(VersionedText.literal("FetchScreen"));
		this.fetchManager = fetchManager;
	}

	@Override
	protected void init() {
		super.init();

		initWidgets();

		this.addRenderableWidget(cancelButton);
	}

	private void initWidgets() {
		cancelButton = buttonWidget(this.width / 2 - 60, this.height - 48, 120, 20, VersionedText.translatable("automodpack.cancel"), button -> {
			cancelButton.active = false;
			cancelFetch();
		});
	}

	private int getFetchesDone() {
		if (fetchManager == null) return 0;
		return fetchManager.fetchesDone.get();
	}

	private int getFetchTotal() {
		return fetchManager == null ? 0 : fetchManager.getFetchDatas().size();
	}

	@Override
	public void versionedBackground(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawPanel(matrices, PANEL_WIDTH, this.height / 2 - 76, this.height / 2 + 76);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		if (fetchManager == null) cancelButton.active = false;
		int centerY = this.height / 2;
		int total = getFetchTotal();
		int done = Math.max(0, Math.min(total, getFetchesDone()));

		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.fetch").withStyle(ChatFormatting.BOLD), this.width / 2, centerY - 54,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Resolving download sources").withStyle(ChatFormatting.GRAY), this.width / 2, centerY - 36,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(done + " / " + total).withStyle(ChatFormatting.YELLOW), this.width / 2, centerY - 12,
				TextColors.WHITE);

		int barLeft = panelLeft(PANEL_WIDTH) + 24;
		int barRight = panelLeft(PANEL_WIDTH) + panelWidth(PANEL_WIDTH) - 24;
		int barY = centerY + 14;
		matrices.fill(barLeft, barY, barRight, barY + 6, TextColors.PANEL_DIVIDER);
		int filledRight = total == 0 ? barLeft : barLeft + (barRight - barLeft) * done / total;
		matrices.fill(barLeft, barY, Math.max(barLeft, filledRight), barY + 6, TextColors.PANEL_ACCENT);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.fetch.found", done).withStyle(ChatFormatting.GRAY), this.width / 2,
				centerY + 34, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	public void cancelFetch() {
		try {
			if (fetchManager != null) fetchManager.cancel();

			new ScreenManager().title();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
