package pl.skidam.automodpack.client.ui.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/*? if >=1.21.10 {*/
import net.minecraft.client.input.InputWithModifiers;
/*?}*/
/*? if >= 1.21.9 {*/
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
/*?}*/

/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/**
 * The one dropdown on every Minecraft version: a vanilla button with a filled chevron after its label, and a menu
 * panel that opens under it. The menu list lives in the screen's children (so clicks, scrolling and the test bridge
 * reach it) but renders in the screen's overlay pass, above widgets and content on every version. The panel itself
 * is drawn here because vanilla draws list panels through different - version-specific - machinery. The chevron
 * travels with the button, so it cannot get lost to per-version render order like the hand-drawn one it replaces.
 */
public final class DropdownWidget extends Button {
	/** Row height of the menu panel; the panel spans exactly the button's width, like a vanilla menu. */
	public static final int MENU_ROW_HEIGHT = 14;
	/** The menu opens this far below the button. */
	private static final int MENU_GAP = 2;
	private static final int CHEVRON_COLOR = 0xFFB0B0B0;
	private static final int PANEL_BACKGROUND = 0xF0100010;
	private static final int PANEL_BORDER = 0x505000FF;

	private final VersionedScreen owner;
	private final Font font;
	private final Minecraft minecraft;
	private List<Component> options = List.of();
	private int selected;
	private int bottomLimit;
	private IntConsumer onPick = index -> {};
	private RowListWidget menu;
	private int panelTop;
	private int panelBottom;

	public DropdownWidget(VersionedScreen owner, Minecraft minecraft, Font font, int x, int y, int width, int height, Component message) {
		/*? if <1.20 {*/
		/*super(x, y, width, height, message, button -> {});
		*//*?} else {*/
		super(x, y, width, height, message, button -> {}, Button.DEFAULT_NARRATION);
		/*?}*/
		this.owner = owner;
		this.minecraft = minecraft;
		this.font = font;
	}

	/** The options the menu lists when opened, which one is highlighted, and what a pick does; re-set whenever they change. */
	public void setOptions(List<Component> options, int selected, int bottomLimit, IntConsumer onPick) {
		this.options = List.copyOf(options);
		this.selected = selected;
		this.bottomLimit = bottomLimit;
		this.onPick = onPick;
	}

	public boolean isMenuOpen() {
		return menu != null;
	}

	public void closeMenu() {
		if (menu == null) return;
		owner.detachMenuChild(menu);
		menu = null;
	}

	@Override
	/*? if >=1.21.10 {*/
	public void onPress(InputWithModifiers input) {
		toggleMenu();
	}
	/*?} else {*/
	/*
	public void onPress() {
		toggleMenu();
	}
	*//*?}*/

	private void toggleMenu() {
		if (menu != null) {
			closeMenu();
			return;
		}
		List<RowListWidget.Row> rows = new ArrayList<>(options.size());
		for (int index = 0; index < options.size(); index++)
			rows.add(new RowListWidget.Row(List.of(VersionedText.literal(VersionedScreen.truncateToWidth(font, options.get(index).getString(), Math.max(1, getWidth() - 12)))
					.withStyle(index == selected ? ChatFormatting.YELLOW : ChatFormatting.WHITE))));
		int top = top() + getHeight() + MENU_GAP;
		int visibleRows = Math.max(1, Math.min(options.size(), (bottomLimit - top) / MENU_ROW_HEIGHT));
		// Four pixels of slack absorb vanilla's phantom-scroll constant, so a fitting menu never shows a scrollbar.
		menu = new RowListWidget(minecraft, getWidth(), minecraft.getWindow().getGuiScaledHeight(), getWidth(), left(), top, top + visibleRows * MENU_ROW_HEIGHT + 4, MENU_ROW_HEIGHT, rows,
				index -> {
					closeMenu();
					onPick.accept(index);
				}, null);
		panelTop = top;
		panelBottom = top + visibleRows * MENU_ROW_HEIGHT + 4;
		owner.attachMenuChild(menu);
	}

	/**
	 * The screen routes clicks here before the widgets: a click on the open menu picks through to the list, a click on
	 * the button itself falls through to its own toggle, and a click anywhere else closes the menu and lets the click go on.
	 */
	public boolean consumeMenuClick(double mouseX, double mouseY, int button) {
		if (menu == null) return false;
		if (menu.isMouseOver(mouseX, mouseY)) {
			menuClicked(mouseX, mouseY, button);
			return true;
		}
		if (isMouseOver(mouseX, mouseY)) return false;
		closeMenu();
		return false;
	}

	/** Renders the open menu panel; the screen calls this in its overlay pass, above content and widgets. */
	/*? if >=26.1 {*/
	public void renderMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (menu == null) return;
		VersionedMatrices matrices = new VersionedMatrices(graphics);
		drawPanel(matrices);
		menu.extractRenderState(graphics, mouseX, mouseY, delta);
	}
	/*?} elif >=1.20 {*/
	/*public void renderMenu(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		if (menu == null) return;
		VersionedMatrices matrices = new VersionedMatrices(graphics);
		drawPanel(matrices);
		menu.render(graphics, mouseX, mouseY, delta);
	}
	*//*?} else {*/
	/*public void renderMenu(PoseStack matrices, int mouseX, int mouseY, float delta) {
		if (menu == null) return;
		VersionedMatrices versionedMatrices = new VersionedMatrices();
		drawPanel(versionedMatrices);
		menu.render(matrices, mouseX, mouseY, delta);
	}
	*//*?}*/

	private void menuClicked(double mouseX, double mouseY, int button) {
		/*? if >= 1.21.9 {*/
		menu.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), true);
		/*?} else {*/
		/*menu.mouseClicked(mouseX, mouseY, button);
		*//*?}*/
	}

	/** The menu panel in the same visual language as the tooltips: dark fill, thin purple border. */
	private void drawPanel(VersionedMatrices matrices) {
		matrices.fill(left() - 1, panelTop - 1, left() + getWidth() + 1, panelTop, PANEL_BORDER);
		matrices.fill(left() - 1, panelBottom, left() + getWidth() + 1, panelBottom + 1, PANEL_BORDER);
		matrices.fill(left() - 1, panelTop, left(), panelBottom, PANEL_BORDER);
		matrices.fill(left() + getWidth(), panelTop, left() + getWidth() + 1, panelBottom, PANEL_BORDER);
		matrices.fill(left(), panelTop, left() + getWidth(), panelBottom, PANEL_BACKGROUND);
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

	/*? if >=26.1 {*/
	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Button is abstract here and leaves its look to subclasses, so the vanilla sprite and label are extracted by hand.
		extractDefaultSprite(graphics);
		extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
		drawChevron(new VersionedMatrices(graphics));
	}
	/*?} elif >=1.21.11 {*/
	/*@Override
	public void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		// Button is abstract here and leaves its look to subclasses, so the vanilla sprite and label are drawn by hand.
		renderDefaultSprite(graphics);
		renderDefaultLabel(graphics.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.NONE));
		drawChevron(new VersionedMatrices(graphics));
	}
	*//*?} elif >=1.20 {*/
	/*@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.renderWidget(graphics, mouseX, mouseY, delta);
		drawChevron(new VersionedMatrices(graphics));
	}
	*//*?} else {*/
	/*@Override
	public void renderButton(PoseStack matrices, int mouseX, int mouseY, float delta) {
		super.renderButton(matrices, mouseX, mouseY, delta);
		drawChevron(new VersionedMatrices());
	}
	*//*?}*/

	/** A small filled chevron right after the centered label; the vanilla font carries no triangle glyph on every version. */
	private void drawChevron(VersionedMatrices matrices) {
		int labelWidth = font.width(getMessage());
		int chevron = left() + Math.max(0, (getWidth() - labelWidth) / 2 + labelWidth + 3);
		int first = top() + 7;
		matrices.fill(chevron, first, chevron + 5, first + 1, CHEVRON_COLOR);
		matrices.fill(chevron + 1, first + 1, chevron + 4, first + 2, CHEVRON_COLOR);
		matrices.fill(chevron + 2, first + 2, chevron + 3, first + 3, CHEVRON_COLOR);
	}
}
