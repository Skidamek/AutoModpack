package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.TextScrollWidget;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** Asks before an installed modpack starts being served from a different address; the flow owns what each answer does. */
public final class OriginChangeConfirmScreen extends VersionedScreen {
	private static final int BODY = 420;
	private static final int LINE = TextScrollWidget.ROW_HEIGHT;
	private final String modpackName;
	private final String previousOrigin;
	private final String newOrigin;
	private final Runnable allowed;
	private final Runnable refused;
	private List<MutableComponent> bodyLines = List.of();
	private int bodyTop = 44;

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
		bodyLines = List.copyOf(lines);
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.originChange.refuse"), button -> refused.run()),
				primaryAction(VersionedText.translatable("automodpack.originChange.allow"), button -> allowed.run()));
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		int bottomLimit = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer) - 4;
		int contentHeight = Math.max(LINE, bodyLines.size() * LINE);
		int available = Math.max(LINE, bottomLimit - 42);
		bodyTop = 42 + Math.max(0, (available - contentHeight) / 2);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.originChange.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		int y = bodyTop;
		for (MutableComponent line : bodyLines) {
			drawCenteredTextWithShadow(matrices, this.font, line, this.width / 2, y, TextColors.WHITE);
			y += LINE;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(refused::run);
	}
}
