package pl.skidam.automodpack.client.ui.versioned;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
/*? if >= 1.20.2 {*/
import net.minecraft.client.gui.components.SpriteIconButton;
/*?} else {*/
/*import net.minecraft.client.gui.components.ImageButton;
*//*?}*/
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

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

		/*? if <1.20.6 {*/
		/*super.render(matrices.getContext(), mouseX, mouseY, delta);
		*//*?}*/
	}

	// This method is to be override by the child classes
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) { }


	/*? if <=1.16.5 {*//*
	public <T extends Element> void addDrawableChild(T child) {
		if (child instanceof ClickableWidget) {
			super.addButton((ClickableWidget) child);
			return;
		}
		super.addChild(child);
	}
	*//*?}*/

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
		return 8;
	}

	protected final int actionButtonWidth(int preferredPanelWidth, int buttonCount) {
		int count = Math.max(1, buttonCount);
		return Math.max(1, (panelWidth(preferredPanelWidth) - actionRowGap() * (count - 1)) / count);
	}

	protected final int actionButtonX(int preferredPanelWidth, int buttonCount, int index) {
		return panelLeft(preferredPanelWidth) + index * (actionButtonWidth(preferredPanelWidth, buttonCount) + actionRowGap());
	}

	protected final int centeredActionButtonX(int preferredPanelWidth, int slotCount, int visibleButtonCount, int index) {
		int buttonWidth = actionButtonWidth(preferredPanelWidth, slotCount);
		int visibleCount = Math.max(1, Math.min(slotCount, visibleButtonCount));
		int groupWidth = visibleCount * buttonWidth + actionRowGap() * (visibleCount - 1);
		return (this.width - groupWidth) / 2 + index * (buttonWidth + actionRowGap());
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

	public static String truncateToWidth(Font font, String text, int maxWidth) {
		if (text == null || text.isEmpty() || maxWidth <= 0) return "";
		if (font.width(text) <= maxWidth) return text;
		String ellipsis = "...";
		if (font.width(ellipsis) >= maxWidth) return fitPrefix(font, text, maxWidth);
		return fitPrefix(font, text, maxWidth - font.width(ellipsis)).stripTrailing() + ellipsis;
	}

	protected static List<String> wrapToWidth(Font font, String text, int maxWidth, int maxLines) {
		List<String> lines = new ArrayList<>();
		if (text == null || text.isBlank() || maxWidth <= 0 || maxLines <= 0) return lines;
		boolean truncated = false;
		for (String rawLine : text.split("\\R", -1)) {
			String remaining = rawLine.strip();
			if (remaining.isEmpty()) {
				if (lines.size() < maxLines) lines.add("");
				continue;
			}
			while (!remaining.isEmpty()) {
				if (lines.size() == maxLines) {
					truncated = true;
					break;
				}
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
			if (truncated) break;
		}
		if (lines.isEmpty()) lines.add("");
		if (truncated) {
			int last = lines.size() - 1;
			lines.set(last, truncateToWidth(font, lines.get(last) + "...", maxWidth));
		}
		return lines;
	}

	private static String fitPrefix(Font font, String text, int maxWidth) {
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) > maxWidth) end--;
		return text.substring(0, end);
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
