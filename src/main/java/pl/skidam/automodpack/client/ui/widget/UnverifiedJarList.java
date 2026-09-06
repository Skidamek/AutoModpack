package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedScissor;
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

/** Nested list of unverified jar paths with their sizes for the unverified confirm screen. */
public final class UnverifiedJarList extends ObjectSelectionList<UnverifiedJarList.Entry> {
	public static final int ROW_HEIGHT = 12;
	private final int contentWidth;

	/** One unverified file: its path as shipped by the pack and its size, 0 when unknown. */
	public record UnverifiedFile(String path, long size) {
		public UnverifiedFile {
			path = Objects.requireNonNull(path, "unverified file path");
			if (size < 0) throw new IllegalArgumentException("Unverified file size is negative");
		}
	}

	public UnverifiedJarList(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<UnverifiedFile> files) {
		/*? if <1.20.3 {*/
		/*super(client, width, height, top, bottom, ROW_HEIGHT);
		*//*?} else {*/
		super(client, width, Math.max(ROW_HEIGHT, bottom - top), top, ROW_HEIGHT);
		/*?}*/
		this.contentWidth = Math.max(1, contentWidth);
		this.centerListVertically = false;
		/*? if <1.20.6 {*/
		/*// Vanilla's list render repaints opaque dirt bands across the whole screen above and below the list;
		// with the screen's text and action rows drawn before the list, those bands erase them (invisible: same dirt as the background).
		this.setRenderTopAndBottom(false);
		*//*?}*/
		for (UnverifiedFile file : Objects.requireNonNull(files, "files")) this.addEntry(new Entry(file));
		if (!this.children().isEmpty()) this.setSelected(this.children().get(0));
	}
	/*? if <1.19.4 {*/
	/*@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(matrices, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?}*/
	/*? if >=1.19.4 <1.20.3 {*/
	/*@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(graphics, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?}*/

	protected int getScrollbarPosition() {
		return Math.min(this.width - 6, this.width / 2 + this.getRowWidth() / 2 + 6);
	}

	@Override
	public int getRowWidth() {
		return this.contentWidth;
	}

	public final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final UnverifiedFile file;

		private Entry(UnverifiedFile file) {
			this.file = file;
		}

		@Override
		public @NotNull Component getNarration() {
			return VersionedText.literal(file.path());
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
			boolean selected = UnverifiedJarList.this.getSelected() == this;
			String sizeText = file.size() > 0 ? UiFormat.formatSize(file.size()) : "";
			int sizeWidth = sizeText.isEmpty() ? 0 : minecraft.font.width(sizeText);
			String label = VersionedScreen.truncateToWidth(minecraft.font, file.path(), Math.max(1, entryWidth - sizeWidth - 6));
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(label), x + 2, y + 1, selected ? TextColors.LIGHT_YELLOW : TextColors.LIGHT_GRAY);
			if (!sizeText.isEmpty()) VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(sizeText), x + entryWidth - sizeWidth, y + 1, TextColors.GRAY);
		}

		/*? if >= 1.21.9 {*/
		@Override
		public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
			return true;
		}
		/*?} else {*/
		/*@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			return true;
		}
		*//*?}*/
	}
}
