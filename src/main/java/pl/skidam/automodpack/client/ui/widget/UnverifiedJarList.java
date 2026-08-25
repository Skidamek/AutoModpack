package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/*? if >= 1.21.9 {*/
import net.minecraft.client.input.MouseButtonEvent;
/*?}*/

/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/** Nested list of unverified jar paths for the unverified confirm screen. */
public final class UnverifiedJarList extends ObjectSelectionList<UnverifiedJarList.Entry> {
	public static final int ROW_HEIGHT = 11;
	private final int contentWidth;

	public UnverifiedJarList(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<String> paths) {
		/*? if <1.20.3 {*/
		/*super(client, width, height, top, bottom, ROW_HEIGHT);
		*//*?} else {*/
		super(client, width, Math.max(ROW_HEIGHT, bottom - top), top, ROW_HEIGHT);
		/*?}*/
		this.contentWidth = Math.max(1, contentWidth);
		this.centerListVertically = false;
		/*? if <1.20.4 {*/
		/*this.setRenderSelection(false);
		*//*?}*/
		for (String path : Objects.requireNonNull(paths, "paths")) this.addEntry(new Entry(path == null ? "" : path));
	}

	protected int getScrollbarPosition() {
		return Math.min(this.width - 6, this.width / 2 + this.getRowWidth() / 2 + 6);
	}

	@Override
	public int getRowWidth() {
		return this.contentWidth;
	}

	public final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final String path;

		private Entry(String path) {
			this.path = path;
		}

		@Override
		public @NotNull Component getNarration() {
			return VersionedText.literal(path);
		}

		/*? if >= 26.1 {*/
		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getContentX(), this.getContentY(), this.getContentWidth());
		}
		/*?} elif >= 1.21.9 {*/
		/*@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), UnverifiedJarList.this.getRowWidth());
		}
		*//*?} else {*/
		/*@Override
		/^? if <1.20 {^/
		/^public void render(PoseStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			VersionedMatrices versionedMatrices = new VersionedMatrices();
		^//^?} else {^/
		public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			VersionedMatrices versionedMatrices = new VersionedMatrices(guiGraphics);
		/^?}^/
			versionedRender(versionedMatrices, x, y, entryWidth);
		}
		*//*?}*/

		private void versionedRender(VersionedMatrices matrices, int x, int y, int entryWidth) {
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, path, Math.max(1, entryWidth - 4))), x + 2, y + 1, TextColors.LIGHT_RED);
		}

		/*? if >= 1.21.9 {*/
		@Override
		public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
			return false;
		}
		/*?} else {*/
		/*@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			return false;
		}
		*//*?}*/
	}
}
