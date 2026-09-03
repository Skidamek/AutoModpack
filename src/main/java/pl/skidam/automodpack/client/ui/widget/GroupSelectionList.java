package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

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

/** Scrolling group rows: every togglable row is a vanilla checkbox, so the bridge and screen readers see the whole list. */
public final class GroupSelectionList extends ContainerObjectSelectionList<GroupSelectionList.Entry> {
	private static final int ROW_HEIGHT = 24;
	public static final int INFO_BUTTON_WIDTH = 20;
	private final int contentWidth;

	public GroupSelectionList(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<Item> items, Consumer<Item> onToggle, Consumer<Item> onInspect) {
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
		Consumer<Item> inspect = Objects.requireNonNull(onInspect, "onInspect");
		for (Item item : Objects.requireNonNull(items, "items")) this.addEntry(new Entry(item, toggle, inspect));
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
		CAPTION,
		HEADER,
		GROUP
	}

	public final class Entry extends ContainerObjectSelectionList.Entry<Entry> {
		private static final int TEXT_MARGIN = 6;
		private final Item item;
		private final AbstractWidget row;
		private final AbstractWidget infoButton;
		private final List<AbstractWidget> children;

		private Entry(Item item, Consumer<Item> onToggle, Consumer<Item> onInspect) {
			this.item = item;
			int rowWidth = GroupSelectionList.this.getRowWidth();
			if (item.kind() == Kind.CAPTION) {
				// The plain "General" section caption is a label, not a control: there is nothing to toggle.
				this.row = null;
				this.infoButton = null;
			} else {
				int mainWidth = item.kind() == Kind.GROUP ? Math.max(1, rowWidth - INFO_BUTTON_WIDTH - ActionAreaLayout.SEAM) : rowWidth;
				AbstractWidget checkbox = VersionedScreen.checkboxWidget(minecraft.font, 0, 0, mainWidth, 20, item.label(), item.selected(), value -> {
					if (value != item.selected()) onToggle.accept(item);
				});
				// Locked rows and inert headers still show their state, but the box is dead: the resolution owns it.
				checkbox.active = item.canToggle();
				if (item.tooltip() != null) {
					/*? if > 1.19.2 {*/
					checkbox.setTooltip(Tooltip.create(item.tooltip()));
					/*?}*/
				}
				this.row = checkbox;
				if (item.kind() == Kind.GROUP) {
					Button inspect = VersionedScreen.buttonWidget(0, 0, INFO_BUTTON_WIDTH, 20, VersionedText.literal("?"), button -> onInspect.accept(item));
					if (item.tooltip() != null) VersionedScreen.setTooltip(inspect, item.tooltip());
					this.infoButton = inspect;
				} else {
					this.infoButton = null;
				}
			}
			this.children = this.row == null ? List.of() : this.infoButton == null ? List.of(this.row) : List.of(this.row, this.infoButton);
		}

		public Item item() {
			return item;
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
			if (row == null) {
				Component label = item.label();
				VersionedScreen.drawTextWithShadow(matrices, minecraft.font, label instanceof MutableComponent mutable ? mutable : VersionedText.literal(label.getString()), x + TEXT_MARGIN, y + 7, TextColors.WHITE);
				return;
			}
			/*? if >=1.19.4 {*/
			row.setX(x);
			row.setY(y);
			/*?} else {*/
			/*row.x = x;
			row.y = y;
			*//*?}*/
			if (infoButton != null) {
				int infoX = x + GroupSelectionList.this.getRowWidth() - INFO_BUTTON_WIDTH;
				/*? if >=1.19.4 {*/
				infoButton.setX(infoX);
				infoButton.setY(y);
				/*?} else {*/
				/*infoButton.x = infoX;
				infoButton.y = y;
				*//*?}*/
			}
			/*? if >=26.1 {*/
			row.extractRenderState(matrices.getContext(), mouseX, mouseY, tickDelta);
			if (infoButton != null) infoButton.extractRenderState(matrices.getContext(), mouseX, mouseY, tickDelta);
			/*?} elif >=1.20 {*/
			/*row.render(matrices.getContext(), mouseX, mouseY, tickDelta);
			if (infoButton != null) infoButton.render(matrices.getContext(), mouseX, mouseY, tickDelta);
			*//*?} else {*/
			/*row.render(matrices.getContext(), mouseX, mouseY, tickDelta);
			if (infoButton != null) infoButton.render(matrices.getContext(), mouseX, mouseY, tickDelta);
			*//*?}*/
		}

		/*? if >= 1.21.9 {*/
		@Override
		public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
			if (row != null) return super.mouseClicked(mouseButtonEvent, bl);
			return false;
		}
		/*?} else {*/
		/*@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (row != null) return super.mouseClicked(mouseX, mouseY, button);
			return false;
		}
		*//*?}*/
	}
}
