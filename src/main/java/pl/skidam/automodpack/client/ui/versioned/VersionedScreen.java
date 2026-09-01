package pl.skidam.automodpack.client.ui.versioned;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
/*? if >=1.20.4 {*/
import net.minecraft.client.gui.components.Checkbox;
/*?}*/
/*? if >= 1.20.2 {*/
import net.minecraft.client.gui.components.SpriteIconButton;
/*?} else {*/
/*import net.minecraft.client.gui.components.ImageButton;
*//*?}*/
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import pl.skidam.automodpack.client.ui.widget.TextScrollWidget;

/*? if >= 1.21.9 {*/
import net.minecraft.client.input.KeyEvent;
/*?}*/

/*? if >=1.21.6 {*/
import net.minecraft.client.renderer.RenderPipelines;
/*?} else if >=1.21.2 {*/
/*import net.minecraft.client.renderer.RenderType;
import java.util.function.Function;
*//*?}*/

/*? if > 1.19.2 {*/
import net.minecraft.client.gui.components.Tooltip;
/*?}*/

/*? if <1.20 {*/
/*import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
*//*?} elif >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} else {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?}*/

import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

public class VersionedScreen extends Screen {

	protected VersionedScreen(Component title) {
		super(title);
	}

	/*? if <1.20 {*/
	/*@Override
	public void render(PoseStack matrix, int mouseX, int mouseY, float delta) {
		VersionedMatrices matrices = new VersionedMatrices();
	*//*?} elif >=26.1 {*/
	@Override
	public void extractRenderState(GuiGraphicsExtractor matrix, int mouseX, int mouseY, float delta) {
		VersionedMatrices matrices = new VersionedMatrices(matrix);
	/*?} else {*/
	/*@Override
	public void render(GuiGraphics matrix, int mouseX, int mouseY, float delta) {
		VersionedMatrices matrices = new VersionedMatrices(matrix);
	*//*?}*/

		// Render background
		/*? if <1.20.2 {*/
		/*super.renderBackground(matrices.getContext());
		*//*?} elif <1.20.6 {*/
		/*super.renderBackground(matrices.getContext(), mouseX, mouseY, delta);
		*//*?} elif >=26.1 {*/
		super.extractRenderState(matrix, mouseX, mouseY, delta);
		/*?} else {*/
		/*super.render(matrix, mouseX, mouseY, delta);
		*//*?}*/

		// Render the rest of our screen
		versionedRender(matrices, mouseX, mouseY, delta);

		for (PlainTextSlot slot : plainTextSlots) drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(slot.text()), this.width / 2, slot.y() + 6, TextColors.WHITE);

		/*? if <1.20.6 {*/
		/*super.render(matrices.getContext(), mouseX, mouseY, delta);
		*//*?}*/
	}

	// This method is to be override by the child classes
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) { }


	/*? if >=1.20 {*/
	public static void drawCenteredTextWithShadow(VersionedMatrices matrices, Font textRenderer, MutableComponent text, int centerX, int y, int color) {
		/*? if >=26.1 {*/
		matrices.getContext().text(textRenderer, text, centerX - textRenderer.width(text) / 2, y, color, true);
		/*?} else {*/
		/*matrices.getContext().drawCenteredString(textRenderer, text, centerX, y, color);
		*//*?}*/
	}
	/*?} else {*/
	/*public static void drawCenteredTextWithShadow(VersionedMatrices matrices, Font textRenderer, MutableComponent text, int centerX, int y, int color) {
		textRenderer.drawShadow(matrices.getContext(), text, (float)(centerX - textRenderer.width(text) / 2), (float)y, color);
	}
	*//*?}*/

	/*? if >=1.20 {*/
	public static void drawTextWithShadow(VersionedMatrices matrices, Font textRenderer, MutableComponent text, int x, int y, int color) {
		/*? if >=26.1 {*/
		matrices.getContext().text(textRenderer, text, x, y, color, true);
		/*?} else {*/
		/*matrices.getContext().drawString(textRenderer, text, x, y, color, true);
		*//*?}*/
	}
	/*?} else {*/
	/*public static void drawTextWithShadow(VersionedMatrices matrices, Font textRenderer, MutableComponent text, int x, int y, int color) {
		textRenderer.drawShadow(matrices.getContext(), text, (float)x, (float)y, color);
	}
	*//*?}*/

	protected final int panelWidth(int preferredWidth) {
		return Math.min(preferredWidth, Math.max(1, this.width - 24));
	}

	protected final int panelLeft(int preferredWidth) {
		return (this.width - panelWidth(preferredWidth)) / 2;
	}

	protected final int actionRowGap() {
		return ActionAreaLayout.GAP;
	}

	protected final ActionDefinition secondaryAction(Component message, Button.OnPress onPress) {
		return action(message, onPress, ActionAreaLayout.Role.SECONDARY, true);
	}

	protected final ActionDefinition optionalAction(Component message, Button.OnPress onPress) {
		return action(message, onPress, ActionAreaLayout.Role.OPTIONAL, true);
	}

	protected final ActionDefinition primaryAction(Component message, Button.OnPress onPress) {
		return action(message, onPress, ActionAreaLayout.Role.PRIMARY, true);
	}

	protected final ActionDefinition navigationAction(Component message, Button.OnPress onPress) {
		return action(message, onPress, ActionAreaLayout.Role.NAVIGATION, true);
	}

	protected final ActionDefinition disabledNavigationAction(Component message) {
		return action(message, button -> {}, ActionAreaLayout.Role.NAVIGATION, false);
	}

	/** A visible but inert row, for status lines that must read as output rather than an offered action. */
	protected final ActionDefinition disabledAction(Component message) {
		return action(message, button -> {}, ActionAreaLayout.Role.OPTIONAL, false);
	}

	private ActionDefinition action(Component message, Button.OnPress onPress, ActionAreaLayout.Role role, boolean enabled) {
		return new ActionDefinition(message, onPress, role, enabled);
	}

	protected final ActionRow actionRow(ActionAreaLayout.RowKind kind, ActionDefinition... actions) {
		return new ActionRow(kind, List.of(actions));
	}

	protected final List<Button> addActionArea(int footerWidth, int bottomY, ActionRow... rows) {
		return addActionArea(footerWidth, bottomY, false, rows);
	}

	protected final List<Button> addActionAreaAt(int footerWidth, int topY, ActionRow... rows) {
		return addActionArea(footerWidth, topY, true, rows);
	}

	protected final int actionAreaTop(int footerWidth, int bottomY, ActionRow... rows) {
		return buildActionArea(footerWidth, bottomY, false, rows).layout().top();
	}

	private List<Button> addActionArea(int footerWidth, int anchorY, boolean fromTop, ActionRow... rows) {
		ActionArea area = buildActionArea(footerWidth, anchorY, fromTop, rows);
		List<Button> buttons = new ArrayList<>(area.layout().placements().size());
		for (ActionAreaLayout.Placement placement : area.layout().placements()) {
			ActionDefinition definition = area.definitions().get(placement.id());
			Button button = buttonWidget(placement.x(), placement.y(), placement.width(), placement.height(), definition.message(), definition.onPress());
			button.active = definition.enabled();
			this.addRenderableWidget(button);
			buttons.add(button);
		}
		return buttons;
	}

	private ActionArea buildActionArea(int footerWidth, int anchorY, boolean fromTop, ActionRow... rows) {
		List<ActionAreaLayout.Row> geometryRows = new ArrayList<>();
		Map<String, ActionDefinition> definitions = new HashMap<>();
		for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
			ActionRow row = rows[rowIndex];
			List<ActionAreaLayout.Action> geometryActions = new ArrayList<>();
			for (int actionIndex = 0; actionIndex < row.actions().size(); actionIndex++) {
				String id = rowIndex + ":" + actionIndex;
				ActionDefinition definition = row.actions().get(actionIndex);
				geometryActions.add(new ActionAreaLayout.Action(id, definition.role()));
				definitions.put(id, definition);
			}
			geometryRows.add(new ActionAreaLayout.Row(row.kind(), geometryActions));
		}

		int left = panelLeft(footerWidth);
		int width = panelWidth(footerWidth);
		ActionAreaLayout.Layout layout = fromTop
				? ActionAreaLayout.fromTop(left, anchorY, width, actionRowGap(), geometryRows)
				: ActionAreaLayout.fromBottom(left, anchorY + ActionAreaLayout.BUTTON_HEIGHT, width, actionRowGap(), geometryRows);
		return new ActionArea(layout, definitions);
	}

	protected final boolean handleBackOnEscape(Runnable backAction) {
		backAction.run();
		return false;
	}

	/** Shows the tooltip while the pointer stays inside the given text bounds, matching vanilla hover-on-text behavior. */
	protected final void showHoverTooltip(VersionedMatrices matrices, Component tooltip, int x, int y, int width, int mouseX, int mouseY) {
		if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + this.font.lineHeight) return;
		/*? if >=1.21.8 {*/
		matrices.getContext().setComponentTooltipForNextFrame(this.font, List.of(tooltip), mouseX, mouseY);
		/*?} elif >=1.20 {*/
		/*setTooltipForNextRenderPass(tooltip);
		*//*?} else {*/
		/*renderTooltip(matrices.getContext(), tooltip, mouseX, mouseY);
		*//*?}*/
	}

	/*? if <1.19.3 {*/
	/*public static Button buttonWidget(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
		return new Button(x, y, width, height, message, onPress);
	}
	*//*?} else {*/
	public static Button buttonWidget(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
		return Button.builder(message, onPress).pos(x, y).size(width, height).build();
	}
	/*?}*/

	/** One input row shared by every screen: the field on the rail, an optional square help button at its right. */
	protected static final int HELP_BUTTON_SIZE = 20;

	protected final EditBox fieldWidget(int x, int y, int railWidth, Component label, Component helpHint, int maxLength) {
		int helpSize = helpHint == null ? 0 : HELP_BUTTON_SIZE + ActionAreaLayout.SEAM;
		int fieldWidth = Math.max(1, railWidth - helpSize);
		EditBox field = new EditBox(this.font, x, y, fieldWidth, ActionAreaLayout.BUTTON_HEIGHT, label);
		field.setMaxLength(maxLength);
		this.addRenderableWidget(field);
		if (helpHint != null) {
			Button help = buttonWidget(x + fieldWidth + ActionAreaLayout.SEAM, y, HELP_BUTTON_SIZE, HELP_BUTTON_SIZE, VersionedText.literal("?"),
					button -> Util.getPlatform().openUri("https://moddedmc.wiki/en/project/automodpack/latest/docs/technicals/certificate"));
			setTooltip(help, helpHint);
			this.addRenderableWidget(help);
		}
		return field;
	}

	/*? if >=1.20.4 {*/
	public static AbstractWidget checkboxWidget(Font font, int x, int y, int width, int height, Component message, boolean selected, Consumer<Boolean> onValueChange) {
		Checkbox.Builder builder = Checkbox.builder(message, font).pos(x, y).selected(selected).onValueChange((box, value) -> onValueChange.accept(value));
		/*? if >=1.21.1 {*/
		builder.maxWidth(Math.max(1, width));
		/*?}*/
		Checkbox checkbox = builder.build();
		/*? if <1.21.1 {*/
		/*checkbox.setWidth(Math.max(1, width));
		*//*?}*/
		return checkbox;
	}
	/*?} else {*/
	/*public static AbstractWidget checkboxWidget(Font font, int x, int y, int width, int height, Component message, boolean selected, Consumer<Boolean> onValueChange) {
		boolean[] checked = { selected };
		return buttonWidget(x, y, width, height, checkboxButtonMessage(message, checked[0]), button -> {
			checked[0] = !checked[0];
			button.setMessage(checkboxButtonMessage(message, checked[0]));
			onValueChange.accept(checked[0]);
		});
	}

	private static Component checkboxButtonMessage(Component message, boolean selected) {
		return VersionedText.literal(selected ? "[x] " : "[ ] ").append(message);
	}
	*//*?}*/

	protected final TextScrollWidget addScrollBody(int contentWidth, int topY, int bottomY, List<String> lines) {
		List<MutableComponent> components = new ArrayList<>();
		for (String line : lines) components.add(VersionedText.literal(line == null ? "" : line));
		return addScrollBody(contentWidth, topY, bottomY, components, false);
	}

	protected final TextScrollWidget addCenteredScrollBody(int contentWidth, int topY, int bottomY, List<? extends Component> lines) {
		return addScrollBody(contentWidth, topY, bottomY, lines, true);
	}

	protected final TextScrollWidget addScrollBody(int contentWidth, int topY, int bottomY, List<? extends Component> lines, boolean center) {
		TextScrollWidget body = new TextScrollWidget(this.minecraft, this.width, this.height, panelWidth(contentWidth), topY, bottomY, lines, center);
		this.addRenderableWidget(body);
		return body;
	}

	protected static MutableComponent blankLine() {
		return VersionedText.literal("");
	}

	protected static List<MutableComponent> wrapParagraph(Font font, String text, int maxWidth, ChatFormatting... styles) {
		List<MutableComponent> lines = new ArrayList<>();
		for (String line : wrapToWidth(font, text, maxWidth)) {
			MutableComponent component = VersionedText.literal(line);
			if (styles.length > 0) component = component.withStyle(styles);
			lines.add(component);
		}
		return lines;
	}

	protected static List<MutableComponent> wrapWithHighlight(Font font, String text, String highlight, int maxWidth, ChatFormatting... highlightStyles) {
		String token = highlight == null ? "" : highlight;
		List<MutableComponent> lines = new ArrayList<>();
		for (String line : wrapToWidth(font, text, maxWidth)) {
			int index = token.isEmpty() ? -1 : line.indexOf(token);
			if (index < 0) {
				lines.add(VersionedText.literal(line));
				continue;
			}
			MutableComponent component = VersionedText.literal(line.substring(0, index));
			MutableComponent marked = VersionedText.literal(token);
			if (highlightStyles.length > 0) marked = marked.withStyle(highlightStyles);
			component.append(marked);
			component.append(VersionedText.literal(line.substring(index + token.length())));
			lines.add(component);
		}
		return lines;
	}

	public static String truncateToWidth(Font font, String text, int maxWidth) {
		if (text == null || text.isEmpty() || maxWidth <= 0) return "";
		if (font.width(text) <= maxWidth) return text;
		String ellipsis = "…";
		if (font.width(ellipsis) >= maxWidth) return fitPrefix(font, text, maxWidth);
		return fitPrefix(font, text, maxWidth - font.width(ellipsis)).stripTrailing() + ellipsis;
	}

	protected static List<String> wrapToWidth(Font font, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		if (text == null || text.isBlank() || maxWidth <= 0) return lines;
		for (String rawLine : text.split("\\R", -1)) {
			String remaining = rawLine.strip();
			if (remaining.isEmpty()) {
				lines.add("");
				continue;
			}
			while (!remaining.isEmpty()) {
				String fitting = fitPrefix(font, remaining, maxWidth);
				int end = fitting.length();
				if (end < remaining.length()) {
					int wordEnd = remaining.lastIndexOf(' ', end - 1);
					if (wordEnd > 0) end = wordEnd;
				}
				if (end == 0) end = 1;
				lines.add(remaining.substring(0, end).strip());
				remaining = remaining.substring(Math.min(end, remaining.length())).strip();
			}
		}
		if (lines.isEmpty()) lines.add("");
		return lines;
	}

	protected static List<String> wrapToWidth(Font font, String text, int maxWidth, int maxLines) {
		List<String> lines = wrapToWidth(font, text, maxWidth);
		if (maxLines <= 0) return new ArrayList<>();
		if (lines.size() <= maxLines) return lines;
		List<String> truncated = new ArrayList<>(lines.subList(0, maxLines));
		int last = truncated.size() - 1;
		truncated.set(last, truncateToWidth(font, truncated.get(last) + "…", maxWidth));
		return truncated;
	}

	private static String fitPrefix(Font font, String text, int maxWidth) {
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) > maxWidth) end--;
		return text.substring(0, end);
	}

	protected final boolean isEnterKey(int keyCode) {
		return keyCode == 257 || keyCode == 335;
	}

	private record PlainTextSlot(String text, int y) {}

	private final List<PlainTextSlot> plainTextSlots = new ArrayList<>();

	/**
	 * Draws the slot's label as plain centered text instead of a disabled button, so page counters
	 * and non-togglable section headers never read as dead clickable widgets. The slots this is
	 * used for sit centered on the screen, matching how vanilla centers button text.
	 */
	protected final void renderAsPlainText(Button button) {
		button.visible = false;
		/*? if >=1.19.4 {*/
		plainTextSlots.add(new PlainTextSlot(button.getMessage().getString(), button.getY()));
		/*?} else {*/
		/*plainTextSlots.add(new PlainTextSlot(button.getMessage().getString(), button.y));
		*//*?}*/
	}

	@Override
	protected void init() {
		plainTextSlots.clear();
	}

	/*? if >= 1.20.2 {*/
	public static Button iconButtonWidget(int x, int y, int buttonWidth, int spriteWidth, Button.OnPress onPress, String spritePath) {
		return iconButtonWidget(x, y, buttonWidth, spriteWidth, onPress, spritePath, VersionedText.literal(""));
	}

	public static Button iconButtonWidget(int x, int y, int buttonWidth, int spriteWidth, Button.OnPress onPress, String spritePath, Component message) {
		Button button = SpriteIconButton.builder(message, onPress, true).sprite(Common.id(spritePath), spriteWidth, spriteWidth).size(buttonWidth, buttonWidth).build();
		button.setPosition(x, y);
		return button;
	}
	/*?} else {*/
	/*public static Button iconButtonWidget(int x, int y, int buttonWidth, int spriteWidth, Button.OnPress onPress, String spritePath) {
		return iconButtonWidget(x, y, buttonWidth, spriteWidth, onPress, spritePath, VersionedText.literal(""));
	}

	public static Button iconButtonWidget(int x, int y, int buttonWidth, int spriteWidth, Button.OnPress onPress, String spritePath, Component message) {
		ImageButton button = new ImageButton(x, y, buttonWidth, buttonWidth, 0, 0, 0, Common.id("textures/gui/sprites/" + spritePath + ".png"), buttonWidth, buttonWidth, onPress);
		button.setMessage(message);
		return button;
	}
	*//*?}*/

	/*? > 1.19.2 {*/
	public static void setTooltip(Button button, Component tooltip) {
		button.setTooltip(Tooltip.create(tooltip));
	}

	/*?} else {*/
	/*public static void setTooltip(Button button, Component tooltip) {
		// Legacy buttons have no tooltip API. Keep their existing message unchanged.
	}
	*//*?}*/

	protected static final class ActionDefinition {
		private final Component message;
		private final Button.OnPress onPress;
		private final ActionAreaLayout.Role role;
		private final boolean enabled;

		private ActionDefinition(Component message, Button.OnPress onPress, ActionAreaLayout.Role role, boolean enabled) {
			this.message = message;
			this.onPress = onPress;
			this.role = role;
			this.enabled = enabled;
		}

		private Component message() {
			return message;
		}

		private Button.OnPress onPress() {
			return onPress;
		}

		private ActionAreaLayout.Role role() {
			return role;
		}

		private boolean enabled() {
			return enabled;
		}
	}

	protected static final class ActionRow {
		private final ActionAreaLayout.RowKind kind;
		private final List<ActionDefinition> actions;

		private ActionRow(ActionAreaLayout.RowKind kind, List<ActionDefinition> actions) {
			this.kind = kind;
			this.actions = List.copyOf(actions);
		}

		private ActionAreaLayout.RowKind kind() {
			return kind;
		}

		private List<ActionDefinition> actions() {
			return actions;
		}
	}

	private record ActionArea(ActionAreaLayout.Layout layout, Map<String, ActionDefinition> definitions) {}

	/*? if <=1.20 {*/
	/*public static void drawTexture(Identifier textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
		/^? if <=1.16.5 {^/
		/^Minecraft.getInstance().getTextureManager().bindTexture(textureID);
		^//^?} else {^/
		RenderSystem.setShaderTexture(0, textureID);
		/^?}^/
		GuiComponent.blit(matrices.getContext(), x, y, u, v, width, height, textureWidth, textureHeight);
	}
	*//*?} else {*/
	public static void drawTexture(Identifier textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
		/*? if >=1.21.6 {*/
		matrices.getContext().blit(RenderPipelines.GUI_TEXTURED, textureID, x, y, u, v, width, height, textureWidth, textureHeight);
		/*?} elif >=1.21.2 {*/
		/*Function<Identifier, RenderType> RenderTypes = RenderType::guiTextured;
		matrices.getContext().blit(RenderTypes, textureID, x, y, u, v, width, height, textureWidth, textureHeight);
		*//*?} else {*/
		/*matrices.getContext().blit(textureID, x, y, u, v, width, height, textureWidth, textureHeight);
		*//*?}*/
	}
	/*?}*/

	/*? if >= 1.21.9 {*/
	@Override
	public boolean keyPressed(KeyEvent event) {
		return onKeyPress(event.key(), event.scancode(), event.modifiers());
	}

	// use this method in code
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		KeyEvent event = new KeyEvent(keyCode, scanCode, modifiers);
		return super.keyPressed(event);
	}
	/*?} else {*/
	/*@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return onKeyPress(keyCode, scanCode, modifiers);
	}

	// use this method in code
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
	*//*?}*/
}
