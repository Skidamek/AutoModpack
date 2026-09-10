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
import pl.skidam.automodpack.client.ui.versioned.VersionedPanels;
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
 * The one dropdown on every Minecraft version: a plain vanilla button whose menu panel opens under it. The menu
 * is not a screen child - the screen forwards clicks, scrolling and the test bridge to the open menus - but it
 * renders in the screen's overlay pass, above widgets and content on every version. The panel itself is drawn
 * here because vanilla draws list panels through different - version-specific - machinery.
 */
public final class DropdownWidget extends Button {
	/** Row height of the menu panel; the panel spans exactly the button's width, like a vanilla menu. */
	public static final int MENU_ROW_HEIGHT = 14;
	/** The menu opens this far below the button. */
	private static final int MENU_GAP = 2;
	/** The sprite rect is the row rect plus this on every side: nine pixels of soft sprite edge plus three of padding. */
	private static final int SPRITE_MARGIN = 12;

	private final Font font;
	private final Minecraft minecraft;
	private List<Component> options = List.of();
	private int selected;
	private int bottomLimit;
	private IntConsumer onPick = index -> {};
	private RowListWidget menu;
	private int panelTop;
	private int panelBottom;

	public DropdownWidget(Minecraft minecraft, Font font, int x, int y, int width, int height, Component message) {
		/*? if <1.20 {*/
		/*super(x, y, width, height, message, button -> {});
		*//*?} else {*/
		super(x, y, width, height, message, button -> {}, Button.DEFAULT_NARRATION);
		/*?}*/
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
		menu = null;
	}

	/** The open menu panel, for the frame's overlay pass and the test bridge; null while closed. */
	public RowListWidget menu() {
		return menu;
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
		menu = new RowListWidget(minecraft, getWidth(), minecraft.getWindow().getGuiScaledHeight(), getWidth(), left(), top, top + visibleRows * MENU_ROW_HEIGHT, MENU_ROW_HEIGHT, rows,
				index -> {
					closeMenu();
					onPick.accept(index);
				});
		panelTop = top;
		panelBottom = top + visibleRows * MENU_ROW_HEIGHT;
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

	/** Routes the pointer wheel to the open menu while the pointer is over it. */
	public boolean consumeMenuScroll(double mouseX, double mouseY, double amount) {
		if (menu == null || !menu.isMouseOver(mouseX, mouseY)) return false;
		menuScrolled(mouseX, mouseY, amount);
		return true;
	}

	private void menuScrolled(double mouseX, double mouseY, double amount) {
		/*? if <1.20.2 {*/
		/*menu.mouseScrolled(mouseX, mouseY, amount);
		*//*?} else {*/
		menu.mouseScrolled(mouseX, mouseY, 0.0, amount);
		/*?}*/
	}

	/** Renders the open menu panel; the screen calls this in its overlay pass, above content and widgets. */
	/*? if >=26.1 {*/
	public void renderMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (menu == null) return;
		VersionedMatrices matrices = new VersionedMatrices(graphics);
		VersionedPanels.beginOverlay(matrices);
		drawPanel(matrices);
		menu.extractRenderState(graphics, mouseX, mouseY, delta);
		VersionedPanels.endOverlay(matrices);
	}
	/*?} elif >=1.20 {*/
	/*public void renderMenu(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		if (menu == null) return;
		VersionedMatrices matrices = new VersionedMatrices(graphics);
		VersionedPanels.beginOverlay(matrices);
		drawPanel(matrices);
		menu.render(graphics, mouseX, mouseY, delta);
		VersionedPanels.endOverlay(matrices);
	}
	*//*?} else {*/
	/*public void renderMenu(PoseStack matrices, int mouseX, int mouseY, float delta) {
		if (menu == null) return;
		VersionedMatrices versionedMatrices = new VersionedMatrices();
		VersionedPanels.beginOverlay(versionedMatrices);
		drawPanel(versionedMatrices);
		menu.render(matrices, mouseX, mouseY, delta);
		VersionedPanels.endOverlay(versionedMatrices);
	}
	*//*?}*/

	private void menuClicked(double mouseX, double mouseY, int button) {
		/*? if >= 1.21.9 {*/
		menu.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), true);
		/*?} else {*/
		/*menu.mouseClicked(mouseX, mouseY, button);
		*//*?}*/
	}

	/** The same vanilla 26.1 tooltip panel the tooltips use, drawn around the menu rows. */
	private void drawPanel(VersionedMatrices matrices) {
		VersionedPanels.drawTooltipPanel(matrices, left() - SPRITE_MARGIN, panelTop - SPRITE_MARGIN, getWidth() + 2 * SPRITE_MARGIN, (panelBottom - panelTop) + 2 * SPRITE_MARGIN);
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
	}
	/*?} elif >=1.21.11 {*/
	/*@Override
	public void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		// Button is abstract here and leaves its look to subclasses, so the vanilla sprite and label are drawn by hand.
		renderDefaultSprite(graphics);
		renderDefaultLabel(graphics.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.NONE));
	}
	*//*?}*/
}
