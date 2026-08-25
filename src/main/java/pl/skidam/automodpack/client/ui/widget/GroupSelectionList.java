package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/*? if > 1.19.2 {*/
import net.minecraft.client.gui.components.Tooltip;
/*?}*/

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

/** Scrolling group rows: category headers as text, optional groups as vanilla checkboxes. */
public final class GroupSelectionList extends ContainerObjectSelectionList<GroupSelectionList.Entry> {
	private static final int ROW_HEIGHT = 24;
	private final int contentWidth;

	public GroupSelectionList(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<Item> items, Consumer<Item> onToggle) {
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
		Consumer<Item> toggle = Objects.requireNonNull(onToggle, "onToggle");
		for (Item item : Objects.requireNonNull(items, "items")) this.addEntry(new Entry(item, toggle));
	}

	protected int getScrollbarPosition() {
		return Math.min(this.width - 6, this.width / 2 + this.getRowWidth() / 2 + 6);
	}

	@Override
	public int getRowWidth() {
		return this.contentWidth;
	}

	public record Item(Kind kind, String id, Component label, Component tooltip, boolean selected, boolean canToggle) {
		public Item {
			Objects.requireNonNull(kind, "kind");
			id = id == null ? "" : id;
			label = Objects.requireNonNull(label, "label");
		}
	}

	public enum Kind {
		HEADER,
		GROUP
	}

	public final class Entry extends ContainerObjectSelectionList.Entry<Entry> {
		private final Item item;
		private final Consumer<Item> onToggle;
		private final AbstractWidget checkbox;
		private final List<AbstractWidget> children;

		private Entry(Item item, Consumer<Item> onToggle) {
			this.item = item;
			this.onToggle = onToggle;
			if (item.kind() == Kind.GROUP && item.canToggle()) {
				this.checkbox = VersionedScreen.checkboxWidget(minecraft.font, 0, 0, GroupSelectionList.this.getRowWidth(), 20, item.label(), item.selected(), value -> {
					if (value != item.selected()) onToggle.accept(item);
				});
				if (item.tooltip() != null) {
					/*? if > 1.19.2 {*/
					this.checkbox.setTooltip(Tooltip.create(item.tooltip()));
					/*?}*/
				}
				this.children = List.of(this.checkbox);
			} else {
				this.checkbox = null;
				this.children = List.of();
			}
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return children;
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return children;
		}

		/*? if >= 26.1 {*/
		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getContentX(), this.getContentY(), this.getContentWidth(), mouseX, mouseY, tickDelta);
		}
		/*?} elif >= 1.21.9 {*/
		/*@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), GroupSelectionList.this.getRowWidth(), mouseX, mouseY, tickDelta);
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
			versionedRender(versionedMatrices, x, y, entryWidth, mouseX, mouseY, tickDelta);
		}
		*//*?}*/

		private void versionedRender(VersionedMatrices matrices, int x, int y, int entryWidth, int mouseX, int mouseY, float tickDelta) {
			if (checkbox != null) {
				/*? if >=1.19.4 {*/
				checkbox.setX(x);
				checkbox.setY(y);
				/*?} else {*/
				/*checkbox.x = x;
				checkbox.y = y;
				*//*?}*/
				/*? if >=26.1 {*/
				checkbox.extractRenderState(matrices.getContext(), mouseX, mouseY, tickDelta);
				/*?} elif >=1.20 {*/
				/*checkbox.render(matrices.getContext(), mouseX, mouseY, tickDelta);
				*//*?} else {*/
				/*checkbox.render(matrices.getContext(), mouseX, mouseY, tickDelta);
				*//*?}*/
				return;
			}
			ChatFormatting color = item.kind() == Kind.HEADER ? ChatFormatting.BOLD : ChatFormatting.GRAY;
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, item.label().getString(), Math.max(1, entryWidth - 8))).withStyle(color), x + 4, y + 6, TextColors.WHITE);
		}

		/*? if >= 1.21.9 {*/
		@Override
		public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
			if (checkbox != null) return super.mouseClicked(mouseButtonEvent, bl);
			if (item.canToggle()) {
				itemToggle();
				return true;
			}
			return false;
		}
		/*?} else {*/
		/*@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (checkbox != null) return super.mouseClicked(mouseX, mouseY, button);
			if (item.canToggle()) {
				itemToggle();
				return true;
			}
			return false;
		}
		*//*?}*/

		private void itemToggle() {
			onToggle.accept(item);
		}
	}
}
