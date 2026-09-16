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

/** Confirms the deterministic replacement of selected groups after a direct conflict. */
public final class GroupConflictScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = ActionAreaLayout.FOOTER_RAIL;

	private final Screen parent;
	private final String preferredName;
	private final String conflictingNames;
	private final Runnable replace;
	private int titleTop;

	public GroupConflictScreen(Screen parent, String preferredName, String conflictingNames, Runnable replace) {
		super(VersionedText.translatable("automodpack.selection.conflictTitle"));
		this.parent = Objects.requireNonNull(parent, "conflict parent");
		this.preferredName = Objects.requireNonNull(preferredName, "preferred group name");
		this.conflictingNames = Objects.requireNonNull(conflictingNames, "conflicting group names");
		this.replace = Objects.requireNonNull(replace, "replacement action");
	}

	@Override
	protected void init() {
		super.init();
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.selection.keepCurrent"), button -> ScreenImpl.setScreen(parent)),
				primaryAction(VersionedText.translatable("automodpack.selection.useGroup", preferredName).withStyle(ChatFormatting.BOLD), button -> confirm()));
		int wrapWidth = Math.max(1, panelWidth(PANEL_WIDTH) - 8);
		String description = VersionedText.translatable("automodpack.selection.conflictDescription", preferredName, conflictingNames, preferredName, preferredName).getString();
		List<MutableComponent> lines = wrapParagraph(this.font, description, wrapWidth, ChatFormatting.GRAY);
		DialogLayout layout = layoutDialogWithActions(28, LINE_HEIGHT, lines.size() * LINE_HEIGHT, 0, footer);
		this.titleTop = layout.titleTop();
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		this.addCenteredScrollBody(PANEL_WIDTH, layout.column().bodyTop(), layout.column().bodyBottom(), lines);
	}

	private void confirm() {
		ScreenImpl.setScreen(parent);
		replace.run();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.conflictTitle").withStyle(ChatFormatting.BOLD), this.width / 2, titleTop, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
