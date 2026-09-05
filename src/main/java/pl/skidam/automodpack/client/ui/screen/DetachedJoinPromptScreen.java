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

	public DetachedJoinPromptScreen(Screen parent, String modpackName, boolean headMatchesActive, Runnable continueJoin, Runnable syncNow) {
		super(VersionedText.translatable("automodpack.detached.title"));
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
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.detached.localState", modpackName).getString(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(headMatchesActive
				? wrapParagraph(this.font, VersionedText.translatable("automodpack.detached.sameGeneration").getString(), wrapWidth)
				: wrapParagraph(this.font, VersionedText.translatable("automodpack.detached.risk").getString(), wrapWidth, ChatFormatting.RED));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.detached.syncAnytime").getString(), wrapWidth));
		ActionRow auxiliary = actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.detached.syncNow"), button -> syncToServer()));
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.detached.continueJoin"), button -> continuePlaying()));
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, auxiliary, footer);
		int bottomLimit = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, auxiliary, footer) - 4;
		addCenteredScrollBody(BODY, 46, bottomLimit, lines);
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.detached.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		if (!modpackName.isBlank()) drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(modpackName).withStyle(ChatFormatting.GRAY), this.width / 2, 30, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::continuePlaying);
	}
}
