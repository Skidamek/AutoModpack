package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** Asks before an installed modpack starts being served from a different address; the flow owns what each answer does. */
public final class OriginChangeConfirmScreen extends VersionedScreen {
	private static final int BODY = 420;
	private final String modpackName;
	private final String previousOrigin;
	private final String newOrigin;
	private final Runnable allowed;
	private final Runnable refused;

	public OriginChangeConfirmScreen(String modpackName, String previousOrigin, String newOrigin, Runnable allowed, Runnable refused) {
		super(VersionedText.translatable("automodpack.originChange.title"));
		this.modpackName = modpackName;
		this.previousOrigin = previousOrigin;
		this.newOrigin = newOrigin;
		this.allowed = allowed;
		this.refused = refused;
	}

	@Override
	protected void init() {
		super.init();
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.originChange.installed", modpackName, previousOrigin).getString(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.originChange.nowServed", newOrigin).getString(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.originChange.explain").getString(), wrapWidth));
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.originChange.refuse"), button -> refused.run()),
				primaryAction(VersionedText.translatable("automodpack.originChange.allow"), button -> allowed.run()));
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		DialogColumn column = layoutDialogColumn(42, actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer), lines.size() * LINE_HEIGHT, 0);
		addCenteredScrollBody(BODY, column.bodyTop(), column.bodyBottom(), lines);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.originChange.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(refused::run);
	}
}
