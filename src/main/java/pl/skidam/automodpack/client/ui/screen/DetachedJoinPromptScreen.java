package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** Warns that the join continues on a locally kept modpack state; the flow owns what each answer does. */
public final class DetachedJoinPromptScreen extends VersionedScreen {
	private static final int BODY = 420;
	private final Screen parent;
	private final String modpackName;
	private final boolean headMatchesActive;
	private final Runnable continueJoin;
	private final Runnable syncNow;
	private boolean finished;
	private int titleTop;

	public DetachedJoinPromptScreen(Screen parent, String modpackName, boolean headMatchesActive, Runnable continueJoin, Runnable syncNow) {
		super(VersionedText.text("automodpack.detached.title"));
		this.parent = parent;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.headMatchesActive = headMatchesActive;
		this.continueJoin = continueJoin;
		this.syncNow = syncNow;
	}

	@Override
	protected void init() {
		super.init();
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.detached.localState", modpackName), wrapWidth));
		lines.add(blankLine());
		lines.addAll(headMatchesActive
				? wrapParagraph(this.font, VersionedText.str("automodpack.detached.sameGeneration"), wrapWidth)
				: wrapParagraph(this.font, VersionedText.str("automodpack.detached.risk"), wrapWidth, ChatFormatting.RED));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.detached.syncAnytime"), wrapWidth));
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.detached.continueJoin"), button -> continuePlaying()),
				primaryAction(VersionedText.text("automodpack.detached.syncNow"), button -> syncToServer()));
		int headerLines = modpackName.isBlank() ? 1 : 2;
		DialogLayout layout = layoutDialogWithActions(28, headerLines * LINE_HEIGHT, lines.size() * LINE_HEIGHT, 0, footer);
		this.titleTop = layout.titleTop();
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		addCenteredScrollBody(BODY, layout.column().bodyTop(), layout.column().bodyBottom(), lines);
	}

	private void continuePlaying() {
		if (finished) return;
		finished = true;
		ScreenImpl.setScreen(parent);
		continueJoin.run();
	}

	private void syncToServer() {
		if (finished) return;
		finished = true;
		syncNow.run();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.detached.title").withStyle(ChatFormatting.BOLD), this.width / 2, titleTop, TextColors.WHITE);
		if (!modpackName.isBlank()) drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(modpackName).withStyle(ChatFormatting.GRAY), this.width / 2, titleTop + LINE_HEIGHT, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::continuePlaying);
	}
}
