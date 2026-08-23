package pl.skidam.automodpack.client.ui.screen;

import java.util.Objects;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/** Confirms the deterministic replacement of selected features after a direct conflict. */
public final class FeatureConflictScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 360;

	private final Screen parent;
	private final String preferredName;
	private final String conflictingNames;
	private final Runnable replace;

	public FeatureConflictScreen(Screen parent, String preferredName, String conflictingNames, Runnable replace) {
		super(VersionedText.translatable("automodpack.selection.conflictTitle"));
		this.parent = Objects.requireNonNull(parent, "conflict parent");
		this.preferredName = Objects.requireNonNull(preferredName, "preferred feature name");
		this.conflictingNames = Objects.requireNonNull(conflictingNames, "conflicting feature names");
		this.replace = Objects.requireNonNull(replace, "replacement action");
	}

	@Override
	protected void init() {
		super.init();
		this.addActionArea(PANEL_WIDTH, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.selection.keepCurrent"), button -> ScreenImpl.setScreen(parent)),
				primaryAction(VersionedText.translatable("automodpack.selection.useFeature", preferredName).withStyle(ChatFormatting.BOLD), button -> confirm())));
	}

	private void confirm() {
		ScreenImpl.setScreen(parent);
		replace.run();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.conflictTitle").withStyle(ChatFormatting.BOLD), this.width / 2, this.height / 2 - 44, TextColors.WHITE);
		String description = VersionedText.translatable("automodpack.selection.conflictDescription", preferredName, conflictingNames, preferredName, preferredName).getString();
		int y = this.height / 2 - 22;
		for (String line : wrapToWidth(this.font, description, panelWidth(PANEL_WIDTH), 3)) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
			y += 12;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
