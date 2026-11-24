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

public final class CrossVersionOfferScreen extends VersionedScreen {
	private static final int BODY = 420;
	private final Screen parent;
	private final String titleKey;
	private final List<String> paragraphs;
	private final Runnable onSwitch;
	private boolean finished;
	private int titleTop;

	public CrossVersionOfferScreen(Screen parent, List<String> paragraphs, Runnable onSwitch) {
		this(parent, "automodpack.crossVersion.title", paragraphs, onSwitch);
	}

	public CrossVersionOfferScreen(Screen parent, String titleKey, List<String> paragraphs, Runnable onSwitch) {
		super(VersionedText.text(titleKey));
		this.parent = parent;
		this.titleKey = titleKey;
		this.paragraphs = List.copyOf(paragraphs);
		this.onSwitch = onSwitch;
	}

	@Override
	protected void init() {
		super.init();
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> lines = new ArrayList<>();
		for (int i = 0; i < paragraphs.size(); i++) {
			if (i > 0) lines.add(blankLine());
			lines.addAll(wrapParagraph(this.font, paragraphs.get(i), wrapWidth));
		}
		ActionRow footer = onSwitch != null
				? actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), button -> back()),
						primaryAction(VersionedText.text("automodpack.crossVersion.switch"), button -> triggerSwitch()))
				: actionRow(ActionAreaLayout.RowKind.FOOTER, primaryAction(VersionedText.text("automodpack.back"), button -> back()));
		DialogLayout layout = layoutDialogWithActions(28, LINE_HEIGHT, lines.size() * LINE_HEIGHT, 0, footer);
		this.titleTop = layout.titleTop();
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		addCenteredScrollBody(BODY, layout.column().bodyTop(), layout.column().bodyBottom(), lines);
	}

	private void triggerSwitch() {
		if (finished || onSwitch == null) return;
		finished = true;
		onSwitch.run();
	}

	private void back() {
		if (finished) return;
		finished = true;
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text(titleKey).withStyle(ChatFormatting.BOLD), this.width / 2, titleTop, TextColors.WHITE);
	}

	@Override
	public void onClose() {
		back();
	}
}
