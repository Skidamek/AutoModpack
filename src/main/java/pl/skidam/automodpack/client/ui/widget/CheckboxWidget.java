package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.init.Common;

/*? if >=1.21.10 {*/
import net.minecraft.client.input.InputWithModifiers;
/*?}*/
/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/**
 * One checkbox drawn exactly like the vanilla 26.1 {@code Checkbox} on every Minecraft version: a 17px sprite box,
 * a label wrapped to at most two rows to the right of it, a toggle on press. Own widget instead of vanilla's so the
 * look, and the test bridge's view of it, never fork by version.
 */
public final class CheckboxWidget extends AbstractButton {
	private static final Identifier TEXTURE = Common.id("textures/gui/sprites/checkbox.png");
	private static final int BOX_SIZE = 17;
	private static final int TEXT_SPACING = 4;
	private static final int MAX_ROWS = 2;
	private final Font font;
	private final Consumer<Boolean> onValueChange;
	private boolean selected;
	private List<MutableComponent> labelLines;

	public CheckboxWidget(Font font, int x, int y, int maxWidth, Component message, boolean selected, Consumer<Boolean> onValueChange) {
		super(x, y, 1, 1, message);
		this.font = font;
		this.onValueChange = onValueChange;
		this.selected = selected;
		relayout(maxWidth);
	}

	public boolean selected() {
		return selected;
	}

	@Override
	public void setMessage(Component message) {
		super.setMessage(message);
		relayout(getWidth());
	}

	/** Wraps the label to the caller's width like vanilla does; the widget fills that width so whole rows stay one click target. */
	private void relayout(int maxWidth) {
		int textWidth = Math.max(1, maxWidth - BOX_SIZE - TEXT_SPACING);
		labelLines = wrapLabel(getMessage().getString(), textWidth);
		this.width = Math.max(1, maxWidth);
		this.height = Math.max(BOX_SIZE, labelLines.size() * font.lineHeight);
	}

	private List<MutableComponent> wrapLabel(String text, int textWidth) {
		List<MutableComponent> lines = VersionedScreen.wrapParagraph(font, text, textWidth);
		return lines.size() > MAX_ROWS ? List.copyOf(lines.subList(0, MAX_ROWS)) : lines;
	}

	/*? if >=1.21.10 {*/
	@Override
	public void onPress(InputWithModifiers input) {
		toggle();
	}
	/*?} else {*/
	/*@Override
	public void onPress() {
		toggle();
	}
	*//*?}*/

	/*? if <1.19.4 {*/
/*@Override
	public void updateNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, getMessage());
	}
*//*?} else {*/
	@Override
	public void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, getMessage());
	}
/*?}*/

	private void toggle() {
		selected = !selected;
		onValueChange.accept(selected);
	}

	/*? if >=26.1 {*/
	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		versionedRender(new VersionedMatrices(graphics));
	}
	/*?} elif >=1.21.11 {*/
	/*@Override
	public void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		versionedRender(new VersionedMatrices(graphics));
	}
	*//*?} elif >=1.20 {*/
	/*@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		versionedRender(new VersionedMatrices(graphics));
	}
	*//*?} else {*/
	/*@Override
	public void renderButton(PoseStack matrices, int mouseX, int mouseY, float delta) {
		versionedRender(new VersionedMatrices());
	}
	*//*?}*/

	private void versionedRender(VersionedMatrices matrices) {
		VersionedScreen.drawTexture(TEXTURE, matrices, left(), top(), selected ? BOX_SIZE : 0, 0, BOX_SIZE, BOX_SIZE, BOX_SIZE * 2, BOX_SIZE);
		int textX = left() + BOX_SIZE + TEXT_SPACING;
		int textY = top() + BOX_SIZE / 2 - labelLines.size() * font.lineHeight / 2;
		for (int line = 0; line < labelLines.size(); line++) VersionedScreen.drawTextWithShadow(matrices, font, labelLines.get(line), textX, textY + line * font.lineHeight, TextColors.WHITE);
	}

	private int left() {
		/*? if >=1.19.4 {*/
		return getX();
		/*?} else {*/
		/*return x;
		*//*?}*/
	}

	private int top() {
		/*? if >=1.19.4 {*/
		return getY();
		/*?} else {*/
		/*return y;
		*//*?}*/
	}
}
