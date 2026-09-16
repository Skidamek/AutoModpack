package pl.skidam.automodpack.client.ui.versioned;

import java.util.ArrayList;
import java.util.function.IntConsumer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
/*? if >=1.20.4 {*/
/*?}*/
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/*? if <1.19.4 {*/
/*import net.minecraft.client.gui.components.Widget;
*//*?} else {*/
import net.minecraft.client.gui.components.Renderable;
/*?}*/

import pl.skidam.automodpack.client.ui.widget.DropdownWidget;
import pl.skidam.automodpack.client.ui.widget.RowListWidget;
import pl.skidam.automodpack.client.ui.widget.TextScrollWidget;

/*? if >= 1.21.9 {*/
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
/*?}*/

/*? if >=1.21.6 {*/
import net.minecraft.client.renderer.RenderPipelines;
/*?} else if >=1.21.2 {*/
/*import net.minecraft.client.renderer.RenderType;
import java.util.function.Function;
*//*?}*/

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
import pl.skidam.automodpack.client.ClientTextures;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.utils.UriOpener;

public class VersionedScreen extends Screen {

	protected VersionedScreen(Component title) {
		super(title);
	}

	// Vanilla keeps its render list private on every version, so the screen mirrors it to control when widgets draw.
	/*? if <1.19.4 {*/
	/*private final List<Widget> renderOrder = new ArrayList<>();
	*//*?} else {*/
	private final List<Renderable> renderOrder = new ArrayList<>();
	/*?}*/

	/*? if <1.19.4 {*/
	/*@Override
	protected <T extends GuiEventListener & Widget & NarratableEntry> T addRenderableWidget(T widget) {
		T added = super.addRenderableWidget(widget);
		renderOrder.add(widget);
		return added;
	}

	private void renderWidgets(PoseStack matrices, int mouseX, int mouseY, float delta) {
		for (Widget widget : renderOrder) widget.render(matrices, mouseX, mouseY, delta);
	}
	*//*?} elif <26.1 {*/
	/*@Override
	protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) {
		T added = super.addRenderableWidget(widget);
		renderOrder.add(widget);
		return added;
	}

	private void renderWidgets(GuiGraphics matrices, int mouseX, int mouseY, float delta) {
		for (Renderable renderable : renderOrder) renderable.render(matrices, mouseX, mouseY, delta);
	}
	*//*?}*/

	// The mirror only exists where the frame runs the widget pass itself; 26.x walks the vanilla list.
	/*? if <26.1 {*/
	@Override
	protected void removeWidget(GuiEventListener listener) {
		super.removeWidget(listener);
		renderOrder.remove(listener);
	}

	@Override
	protected void clearWidgets() {
		super.clearWidgets();
		renderOrder.clear();
	}
	/*?}*/

	/**
	 * The one frame order on every version: background, widgets, the screen's own content over them, then the
	 * tooltip on top. Vanilla splits these phases differently per version (widgets over custom content on the
	 * legacy render, custom content over widgets since 1.20.6), which made every overlay's stacking a gamble.
	 * Since 1.21.8 vanilla draws the background itself before {@code render} (its blur budget allows exactly one
	 * blur per frame), so there we must not touch the background again.
	 */
	/*? if <1.20 {*/
	/*@Override
	public void render(PoseStack matrix, int mouseX, int mouseY, float delta) {
		VersionedMatrices matrices = new VersionedMatrices();
		super.renderBackground(matrix);
		this.renderWidgets(matrix, mouseX, mouseY, delta);
	*//*?} elif >=26.1 {*/
	@Override
	public void extractRenderState(GuiGraphicsExtractor matrix, int mouseX, int mouseY, float delta) {
		VersionedMatrices matrices = new VersionedMatrices(matrix);
		super.extractRenderState(matrix, mouseX, mouseY, delta);
	/*?} elif <1.20.2 {*/
	/*@Override
	public void render(GuiGraphics matrix, int mouseX, int mouseY, float delta) {
		VersionedMatrices matrices = new VersionedMatrices(matrix);
		super.renderBackground(matrices.getContext());
		this.renderWidgets(matrices.getContext(), mouseX, mouseY, delta);
	*//*?} elif <1.21.8 {*/
	/*@Override
	public void render(GuiGraphics matrix, int mouseX, int mouseY, float delta) {
		VersionedMatrices matrices = new VersionedMatrices(matrix);
		super.renderBackground(matrices.getContext(), mouseX, mouseY, delta);
		this.renderWidgets(matrices.getContext(), mouseX, mouseY, delta);
	*//*?} else {*/
	/*@Override
	public void render(GuiGraphics matrix, int mouseX, int mouseY, float delta) {
		VersionedMatrices matrices = new VersionedMatrices(matrix);
		this.renderWidgets(matrices.getContext(), mouseX, mouseY, delta);
	*//*?}*/
		versionedRender(matrices, mouseX, mouseY, delta);
		renderMenus(matrices, mouseX, mouseY, delta);
		renderTooltips(matrices, mouseX, mouseY);
	}

	/** The one dropdown factory; registered so the frame can render its menu on top and close it on outside clicks. */
	protected final DropdownWidget dropdownWidget(int x, int y, int width, int height, Component message) {
		DropdownWidget dropdown = new DropdownWidget(this.minecraft, this.font, x, y, width, height, message);
		this.dropdowns.add(dropdown);
		this.addRenderableWidget(dropdown);
		return dropdown;
	}

	/** True while any dropdown menu is open; screens gate the UI underneath on it. */
	protected final boolean menuOpen() {
		for (DropdownWidget dropdown : dropdowns) if (dropdown.isMenuOpen()) return true;
		return false;
	}

	/** Closes every open dropdown menu; true when something was open, so escape can close the menu before the screen. */
	protected final boolean closeOpenMenus() {
		boolean open = menuOpen();
		for (DropdownWidget dropdown : dropdowns) dropdown.closeMenu();
		return open;
	}

	/** The currently open dropdown menus; the frame renders them and the test bridge reads their rows. */
	public final List<RowListWidget> openMenus() {
		List<RowListWidget> menus = new ArrayList<>();
		for (DropdownWidget dropdown : dropdowns) if (dropdown.isMenuOpen()) menus.add(dropdown.menu());
		return menus;
	}

	private void renderMenus(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		/*? if <1.21.8 {*/
		/*// Pending screen text must rasterize before the menu panel fills, or the later batch flush paints the panel under it.
		Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
		*//*?}*/
		for (DropdownWidget dropdown : dropdowns) dropdown.renderMenu(matrices.getContext(), mouseX, mouseY, delta);
	}

	/*? if >= 1.21.9 {*/
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		if (event.button() == 0) for (DropdownWidget dropdown : dropdowns) if (dropdown.consumeMenuClick(event.x(), event.y(), event.button())) return true;
		return super.mouseClicked(event, bl);
	}
	/*?} else {*/
	/*@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) for (DropdownWidget dropdown : dropdowns) if (dropdown.consumeMenuClick(mouseX, mouseY, button)) return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}
	*//*?}*/

	/*? if <1.20.2 {*/
	/*@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		for (DropdownWidget dropdown : dropdowns) if (dropdown.consumeMenuScroll(mouseX, mouseY, delta)) return true;
		return super.mouseScrolled(mouseX, mouseY, delta);
	}
	*//*?} else {*/
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double xDelta, double yDelta) {
		for (DropdownWidget dropdown : dropdowns) if (dropdown.consumeMenuScroll(mouseX, mouseY, yDelta)) return true;
		return super.mouseScrolled(mouseX, mouseY, xDelta, yDelta);
	}
	/*?}*/

	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) { }

	/** Re-runs init so every widget reflects the current fields; for when one change reshapes the whole screen. */
	protected void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
	}


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

	protected final ActionDefinition disabledAction(Component message) {
		return action(message, button -> {}, ActionAreaLayout.Role.OPTIONAL, false);
	}

	private ActionDefinition action(Component message, Button.OnPress onPress, ActionAreaLayout.Role role, boolean enabled) {
		return new ActionDefinition(message, onPress, role, enabled);
	}

	protected final ActionRow actionRow(ActionAreaLayout.RowKind kind, ActionDefinition... actions) {
		return new ActionRow(kind, List.of(actions));
	}

	protected final List<AbstractWidget> addActionArea(int footerWidth, int bottomY, ActionRow... rows) {
		return addActionArea(footerWidth, bottomY, false, rows);
	}

	protected final List<AbstractWidget> addActionAreaAt(int footerWidth, int topY, ActionRow... rows) {
		return addActionArea(footerWidth, topY, true, rows);
	}

	protected final int actionAreaTop(int footerWidth, int bottomY, ActionRow... rows) {
		return buildActionArea(footerWidth, bottomY, false, rows).layout().top();
	}

	/** One laid-out dialog: where the title line(s) sit, the body window, and where the action rows go. */
	protected record DialogLayout(DialogColumn column, int actionsTop, int titleTop) {}

	/**
	 * The dialog layout rule of the mod: the action rows pin to the bottom rail and the dialog - title, body
	 * and optional pinned stack - centers as one block between the top reserve and those rows, the way vanilla
	 * centers a confirm dialog around its message. The title travels with the block; only a body that cannot
	 * fit scrolls, while the title pins above it and the stack pins above the rows.
	 */
	protected final DialogLayout layoutDialogWithActions(int topReserve, int titleHeight, int bodyHeight, int stackHeight, ActionRow... rows) {
		int actionsTop = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows);
		int header = titleHeight + (titleHeight > 0 ? LINE_HEIGHT : 0);
		DialogColumn column = layoutDialogColumn(topReserve + header, actionsTop, bodyHeight, stackHeight);
		int titleTop = column.scrolls() ? topReserve : column.bodyTop() - header;
		return new DialogLayout(column, actionsTop, titleTop);
	}

	private List<AbstractWidget> addActionArea(int footerWidth, int anchorY, boolean fromTop, ActionRow... rows) {
		ActionArea area = buildActionArea(footerWidth, anchorY, fromTop, rows);
		List<AbstractWidget> widgets = new ArrayList<>(area.layout().placements().size());
		for (ActionAreaLayout.Placement placement : area.layout().placements()) {
			ActionDefinition definition = area.definitions().get(placement.id());
			AbstractWidget widget = buttonWidget(placement.x(), placement.y(), placement.width(), placement.height(), definition.message(), definition.onPress());
			widget.active = definition.enabled();
			definition.widget = widget;
			this.addRenderableWidget(widget);
			widgets.add(widget);
		}
		return widgets;
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
	protected final void showHoverTooltip(Component tooltip, int x, int y, int width, int mouseX, int mouseY) {
		if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + this.font.lineHeight) return;
		showComponentTooltip(tooltip, mouseX, mouseY);
	}

	/** Claims the frame's tooltip at the pointer; works as a static twin too, so list rows outside the screen hierarchy can call it. */
	public static void showComponentTooltip(Component tooltip, int mouseX, int mouseY) {
		VersionedScreen versioned = currentScreen();
		if (versioned != null) versioned.frameTooltip = tooltip;
	}

	public static void setTooltip(AbstractWidget widget, Component tooltip) {
		Objects.requireNonNull(widget, "tooltip widget");
		Objects.requireNonNull(tooltip, "tooltip");
		VersionedScreen versioned = currentScreen();
		if (versioned != null) versioned.widgetTooltips.put(widget, tooltip);
	}

	public static int widgetY(AbstractWidget widget) {
		/*? if >=1.19.4 {*/
		return widget.getY();
		/*?} else {*/
		/*return widget.y;
		*//*?}*/
	}

	/** The screen the game currently shows when it is one of ours; the accessor is renamed on 26.2. */
	private static VersionedScreen currentScreen() {
		/*? if >=26.2 {*/
		return Minecraft.getInstance().gui.screen() instanceof VersionedScreen versioned ? versioned : null;
		/*?} else {*/
		/*return Minecraft.getInstance().screen instanceof VersionedScreen versioned ? versioned : null;
		*//*?}*/
	}

	// One tooltip per frame: a row or list hover claims it first, else the first hovered widget with a registered tooltip draws.
	// Vanilla attaches tooltips to widgets and paints them per version; here the screen owns both, so the look cannot fork.
	private Component frameTooltip;
	private final Map<AbstractWidget, Component> widgetTooltips = new LinkedHashMap<>();
	private final List<DropdownWidget> dropdowns = new ArrayList<>();

	@Override
	protected void init() {
		super.init();
		// Cleared on re-init so replaced widgets cannot answer for their successors.
		widgetTooltips.clear();
		dropdowns.clear();
	}

	private void renderTooltips(VersionedMatrices matrices, int mouseX, int mouseY) {
		// An open menu is modal: the content under it keeps the pointer, so its tooltip must not poke through.
		if (menuOpen()) {
			frameTooltip = null;
			return;
		}
		Component tooltip = frameTooltip;
		frameTooltip = null;
		if (tooltip == null) {
			for (Map.Entry<AbstractWidget, Component> entry : widgetTooltips.entrySet()) {
				if (entry.getKey().isMouseOver(mouseX, mouseY)) {
					tooltip = entry.getValue();
					break;
				}
			}
		}
		if (tooltip != null) VersionedTooltips.draw(this.font, matrices, tooltip, mouseX, mouseY, this.width, this.height);
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

	/** The published security docs every in-game link points at; the path stays in step with docs/security.mdx. */
	public static final String SECURITY_DOCS_URL = "https://moddedmc.wiki/en/project/automodpack/latest/docs/security";

	protected final EditBox fieldWidget(int x, int y, int railWidth, Component label, Component helpHint, int maxLength) {
		int helpSize = helpHint == null ? 0 : HELP_BUTTON_SIZE + ActionAreaLayout.SEAM;
		int fieldWidth = Math.max(1, railWidth - helpSize);
		EditBox field = new EditBox(this.font, x, y, fieldWidth, ActionAreaLayout.BUTTON_HEIGHT, label);
		field.setMaxLength(maxLength);
		this.addRenderableWidget(field);
		if (helpHint != null) {
			Button help = buttonWidget(x + fieldWidth + ActionAreaLayout.SEAM, y, HELP_BUTTON_SIZE, HELP_BUTTON_SIZE, VersionedText.literal("?"),
					button -> UriOpener.openUri(SECURITY_DOCS_URL));
			setTooltip(help, helpHint);
			this.addRenderableWidget(help);
		}
		return field;
	}

	protected final TextScrollWidget addScrollBody(int contentWidth, int topY, int bottomY, List<String> lines) {
		List<MutableComponent> components = new ArrayList<>();
		for (String line : lines) components.add(VersionedText.literal(line == null ? "" : line));
		return addScrollBody(contentWidth, topY, bottomY, components, false);
	}

	protected final TextScrollWidget addCenteredScrollBody(int contentWidth, int topY, int bottomY, List<? extends Component> lines) {
		return addScrollBody(contentWidth, topY, bottomY, lines, true);
	}

	protected final TextScrollWidget addScrollBody(int contentWidth, int topY, int bottomY, List<? extends Component> lines, boolean center) {
		// The window shrinks to a whole number of rows: a bottom between the line-grid points would cut the last
		// visible row mid-glyph, which reads as overlapping lines once the body overflows and starts scrolling.
		int window = Math.max(0, bottomY - topY);
		bottomY = topY + window / LINE_HEIGHT * LINE_HEIGHT;
		TextScrollWidget body = new TextScrollWidget(this.minecraft, this.width, this.height, panelWidth(contentWidth), topY, bottomY, lines, center);
		this.addRenderableWidget(body);
		return body;
	}

	protected static MutableComponent blankLine() {
		return VersionedText.literal("");
	}

	/** The vanilla font line height; every dialog line advance goes through this constant, never a raw 9. */
	public static final int LINE_HEIGHT = 9;

	/** Draws centered lines advancing by LINE_HEIGHT and returns the y below the last line. */
	protected final int drawCenteredLines(VersionedMatrices matrices, List<? extends Component> lines, int y) {
		for (Component line : lines) {
			drawCenteredTextWithShadow(matrices, this.font, line instanceof MutableComponent mutable ? mutable : VersionedText.literal(line.getString()), this.width / 2, y, TextColors.WHITE);
			y += LINE_HEIGHT;
		}
		return y;
	}

	/** The shared read-countdown line: centered gray, the same look on every gate. */
	protected final void drawCountdown(VersionedMatrices matrices, MutableComponent countdown, int y) {
		drawCenteredTextWithShadow(matrices, this.font, countdown.withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
	}

	/** One dialog column: the scrollable body window and where the pinned stack under it starts. */
	protected record DialogColumn(int bodyTop, int bodyBottom, boolean scrolls, int stackTop) {}

	/**
	 * Lays a dialog out as one column — body, then an optional pinned stack — above the footer. A column
	 * that fits centers in the space between the top reserve and the footer; only a real overflow clips
	 * the body into the remaining window while the stack pins above the footer.
	 */
	protected final DialogColumn layoutDialogColumn(int topReserve, int footerTop, int contentHeight, int stackHeight) {
		int bottomLimit = footerTop - 4;
		int available = Math.max(0, bottomLimit - topReserve);
		int blockHeight = stackHeight > 0 ? contentHeight + ActionAreaLayout.SEAM + stackHeight : contentHeight;
		if (blockHeight <= available) {
			int blockTop = topReserve + (available - blockHeight) / 2;
			return new DialogColumn(blockTop, blockTop + contentHeight, false, blockTop + contentHeight + ActionAreaLayout.SEAM);
		}
		int stackTop = Math.max(topReserve, bottomLimit - stackHeight);
		int bodyBottom = Math.max(topReserve + LINE_HEIGHT, stackTop - (stackHeight > 0 ? ActionAreaLayout.GAP : 0));
		return new DialogColumn(topReserve, bodyBottom, true, stackTop);
	}

	public static List<MutableComponent> wrapParagraph(Font font, String text, int maxWidth, ChatFormatting... styles) {
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
		if (text == null || text.isBlank() || maxWidth <= 0) return new ArrayList<>();
		List<String> lines = new ArrayList<>();
		wrapLines(font, text, maxWidth, Integer.MAX_VALUE, lines);
		return lines;
	}

	protected static List<String> wrapToWidth(Font font, String text, int maxWidth, int maxLines) {
		if (maxLines <= 0 || text == null || text.isBlank() || maxWidth <= 0) return new ArrayList<>();
		List<String> lines = new ArrayList<>();
		boolean overflow = wrapLines(font, text, maxWidth, maxLines, lines);
		if (!overflow) return lines;
		List<String> truncated = new ArrayList<>(lines.subList(0, maxLines));
		int last = truncated.size() - 1;
		truncated.set(last, truncateToWidth(font, truncated.get(last) + "…", maxWidth));
		return truncated;
	}

	/** Fills lines with wrapped lines; stops once limit is reached and reports whether content remained past it. */
	private static boolean wrapLines(Font font, String text, int maxWidth, int limit, List<String> lines) {
		for (String rawLine : text.split("\\R", -1)) {
			String remaining = rawLine.strip();
			if (remaining.isEmpty()) {
				if (lines.size() >= limit) return true;
				lines.add("");
				continue;
			}
			while (!remaining.isEmpty()) {
				if (lines.size() >= limit) return true;
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
		return false;
	}

	/** Largest prefix fitting maxWidth; width grows monotonically with length, so the fit binary searches in O(n log n). */
	private static String fitPrefix(Font font, String text, int maxWidth) {
		int lo = 0;
		int hi = text.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (font.width(text.substring(0, mid)) <= maxWidth) lo = mid;
			else hi = mid - 1;
		}
		return text.substring(0, lo);
	}

	protected final boolean isEnterKey(int keyCode) {
		return keyCode == 257 || keyCode == 335;
	}

	/** One icon button for every version: our texture through the TextureManager, never the pack-resolvable gui atlas. */
	public static Button iconButtonWidget(int x, int y, int buttonWidth, int spriteWidth, Button.OnPress onPress, String spritePath) {
		return iconButtonWidget(x, y, buttonWidth, spriteWidth, onPress, spritePath, VersionedText.literal(""));
	}

	public static Button iconButtonWidget(int x, int y, int buttonWidth, int spriteWidth, Button.OnPress onPress, String spritePath, Component message) {
		return new VersionedIconButton(x, y, buttonWidth, spriteWidth, onPress, Common.id("textures/gui/sprites/" + spritePath + ".png"), message);
	}

	protected static final class ActionDefinition {
		private final Component message;
		private final Button.OnPress onPress;
		private final ActionAreaLayout.Role role;
		private final boolean enabled;
		private AbstractWidget widget;

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

		/** The widget this action was built into by the last {@code addActionArea} call, so screens never replay row conditionals as indices. */
		public AbstractWidget widget() {
			return widget;
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
		ClientTextures.ensureRegistered();
		/^? if <=1.16.5 {^/
		/^Minecraft.getInstance().getTextureManager().bindTexture(textureID);
		^//^?} else {^/
		RenderSystem.setShaderTexture(0, textureID);
		/^?}^/
		GuiComponent.blit(matrices.getContext(), x, y, u, v, width, height, textureWidth, textureHeight);
	}
	*//*?} else {*/
	public static void drawTexture(Identifier textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
		ClientTextures.ensureRegistered();
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

	// Subclasses override this instead of keyPressed, whose signature differs across versions
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		KeyEvent event = new KeyEvent(keyCode, scanCode, modifiers);
		return super.keyPressed(event);
	}
	/*?} else {*/
	/*@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return onKeyPress(keyCode, scanCode, modifiers);
	}

	// Subclasses override this instead of keyPressed, whose signature differs across versions
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
	*//*?}*/
}
