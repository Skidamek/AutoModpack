package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.WaitingPresentation;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

public class PreparingScreen extends VersionedScreen {
	private final long startedAtNanos = System.nanoTime();
	private final Runnable onCancel;

	public PreparingScreen(Runnable onCancel) {
		super(VersionedText.translatable("automodpack.preparing.title"));
		this.onCancel = onCancel;
	}

	@Override
	protected void init() {
		super.init();
		if (onCancel == null) return;
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height / 2 + 56, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.cancel"), button -> onCancel.run())));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		WaitingPresentation.render(matrices, this.font, this.width, this.height, System.nanoTime() - startedAtNanos);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return onCancel != null && handleBackOnEscape(onCancel);
	}
}
