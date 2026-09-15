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

/** Offers the server's modpack before anything syncs; the flow owns what each answer does. */
public final class ModpackOfferScreen extends VersionedScreen {
	private static final int BODY = 420;
	private final Screen parent;
	private final Runnable syncModpack;
	private final Runnable joinWithout;
	private final Runnable cancel;
	private boolean finished;
	private int titleTop;

	public ModpackOfferScreen(Screen parent, Runnable syncModpack, Runnable joinWithout, Runnable cancel) {
		super(VersionedText.translatable("automodpack.offer.title"));
		this.parent = parent;
		this.syncModpack = syncModpack;
		this.joinWithout = joinWithout;
		this.cancel = cancel;
	}

	@Override
	protected void init() {
		super.init();
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.offer.serverModpack").getString(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.offer.syncAnytime").getString(), wrapWidth));
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.offer.joinWithout"), button -> joinWithoutIt()),
				primaryAction(VersionedText.translatable("automodpack.offer.syncModpack"), button -> syncToServer()));
		DialogLayout layout = layoutDialogWithActions(28, LINE_HEIGHT, lines.size() * LINE_HEIGHT, 0, footer);
		this.titleTop = layout.titleTop();
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		addCenteredScrollBody(BODY, layout.column().bodyTop(), layout.column().bodyBottom(), lines);
	}

	private void joinWithoutIt() {
		if (finished) return;
		finished = true;
		ScreenImpl.setScreen(parent);
		joinWithout.run();
	}

	private void syncToServer() {
		if (finished) return;
		finished = true;
		syncModpack.run();
	}

	private void cancelJoin() {
		if (finished) return;
		finished = true;
		cancel.run();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.offer.title").withStyle(ChatFormatting.BOLD), this.width / 2, titleTop, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::cancelJoin);
	}
}
