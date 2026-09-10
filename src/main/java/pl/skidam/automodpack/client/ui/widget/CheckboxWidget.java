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
 * One checkbox drawn exactly like the vanilla 26.1 {@code Checkbox} on every Minecraft version: a 17px sprite box
 * (unchecked, checked or the indeterminate dash), a label wrapped to at most two rows to the right of it, a toggle
 * on press. Own widget instead of vanilla's so the look, and the test bridge's view of it, never fork by version.
 */
public class CheckboxWidget extends AbstractButton {
	private static final Identifier TEXTURE = Common.id("textures/gui/sprites/checkbox.png");
	protected static final int BOX_SIZE = 17;
	protected static final int TEXT_SPACING = 4;
	private static final int MAX_ROWS = 2;

	/** Checked, unchecked, and the indeterminate dash for "some of it is in". */
	public enum State { UNCHECKED, CHECKED, PARTIAL }

	private final Font font;
	private final Consumer<Boolean> onValueChange;
	private final int rightReserve;
	private State state;
	// The plain label we were given; on 26.x getMessage() swaps in a gray-styled copy while the widget is disabled, which must not reach the rendered lines.
	private Component label;
	private List<MutableComponent> labelLines;

	public CheckboxWidget(Font font, int x, int y, int maxWidth, Component message, boolean selected, Consumer<Boolean> onValueChange) {
		this(font, x, y, maxWidth, message, selected ? State.CHECKED : State.UNCHECKED, onValueChange, 0);
	}

	public CheckboxWidget(Font font, int x, int y, int maxWidth, Component message, State state, Consumer<Boolean> onValueChange) {
		this(font, x, y, maxWidth, message, state, onValueChange, 0);
	}

	/** {@code rightReserve} is width kept clear right of the label for subclass dressing (a counter, for one). */
	protected CheckboxWidget(Font font, int x, int y, int maxWidth, Component message, State state, Consumer<Boolean> onValueChange, int rightReserve) {
		super(x, y, 1, 1, message);
		this.font = font;
		this.onValueChange = onValueChange;
		this.state = state;
		this.rightReserve = rightReserve;
		this.label = message;
		relayout(maxWidth);
	}

	public boolean selected() {
		return state == State.CHECKED;
	}

	public boolean partial() {
		return state == State.PARTIAL;
	}

	@Override
	public void setMessage(Component message) {
		this.label = message;
		super.setMessage(message);
		relayout(getWidth());
	}

	/** Wraps the label to the caller's width like vanilla does; the widget fills that width so whole rows stay one click target. */
	private void relayout(int maxWidth) {
		labelLines = wrapLabel(label, textWidth(maxWidth));
		this.width = Math.max(1, maxWidth);
		this.height = Math.max(BOX_SIZE, labelLines.size() * font.lineHeight);
	}

	private int textWidth(int maxWidth) {
		return Math.max(1, maxWidth - BOX_SIZE - TEXT_SPACING - rightReserve);
	}

	private List<MutableComponent> wrapLabel(Component message, int textWidth) {
		List<MutableComponent> lines = VersionedScreen.wrapParagraph(font, message.getString(), textWidth);
		return (lines.size() > MAX_ROWS ? List.copyOf(lines.subList(0, MAX_ROWS)) : lines).stream()
				.<MutableComponent>map(line -> line.withStyle(message.getStyle())).toList();
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
		// An indeterminate box resolves to checked on click, like every tri-state checkbox players already know.
		state = state == State.CHECKED ? State.UNCHECKED : State.CHECKED;
		onValueChange.accept(selected());
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

	protected void versionedRender(VersionedMatrices matrices) {
		drawBox(matrices, left(), top(), state);
		int textX = left() + BOX_SIZE + TEXT_SPACING;
		int textY = top() + BOX_SIZE / 2 - labelLines.size() * font.lineHeight / 2;
		for (int line = 0; line < labelLines.size(); line++) VersionedScreen.drawTextWithShadow(matrices, font, labelLines.get(line), textX, textY + line * font.lineHeight, TextColors.WHITE);
	}

	/** Draws the box in one of its three states from the shared sprite strip. */
	protected static void drawBox(VersionedMatrices matrices, int x, int y, State state) {
		VersionedScreen.drawTexture(TEXTURE, matrices, x, y, state.ordinal() * BOX_SIZE, 0, BOX_SIZE, BOX_SIZE, BOX_SIZE * 3, BOX_SIZE);
	}

	protected int left() {
		/*? if >=1.19.4 {*/
		return getX();
		/*?} else {*/
		/*return x;
		*//*?}*/
	}

	protected int top() {
		/*? if >=1.19.4 {*/
		return getY();
		/*?} else {*/
		/*return y;
		*//*?}*/
	}
}
