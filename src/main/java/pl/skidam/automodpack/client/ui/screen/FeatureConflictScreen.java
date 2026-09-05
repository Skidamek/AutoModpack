package pl.skidam.automodpack.client.ui.screen;

import java.util.List;
import java.util.Objects;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** Confirms the deterministic replacement of selected features after a direct conflict. */
public final class FeatureConflictScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = ActionAreaLayout.FOOTER_RAIL;

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
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.selection.keepCurrent"), button -> ScreenImpl.setScreen(parent)),
				primaryAction(VersionedText.translatable("automodpack.selection.useFeature", preferredName).withStyle(ChatFormatting.BOLD), button -> confirm()));
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		int wrapWidth = Math.max(1, panelWidth(PANEL_WIDTH) - 8);
		String description = VersionedText.translatable("automodpack.selection.conflictDescription", preferredName, conflictingNames, preferredName, preferredName).getString();
		List<MutableComponent> lines = wrapParagraph(this.font, description, wrapWidth, ChatFormatting.GRAY);
		DialogColumn column = layoutDialogColumn(42, actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer), lines.size() * LINE_HEIGHT, 0);
		this.addCenteredScrollBody(PANEL_WIDTH, column.bodyTop(), column.bodyBottom(), lines);
	}

	private void confirm() {
		ScreenImpl.setScreen(parent);
		replace.run();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.conflictTitle").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
