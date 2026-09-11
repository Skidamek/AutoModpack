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
public final class GroupSelectionList extends ContainerObjectSelectionList<GroupSelectionList.Entry> implements RowViewport {
	private static final int ROW_HEIGHT = 24;

	private static Component filesLabel() {
		return VersionedText.translatable("automodpack.selection.groupFiles");
	}

	/** Group rows span the whole list like the shared ChromelessList lists do; vanilla caps the row width, which squashed every row into the center. */
	@Override
	public int getRowWidth() {
		return this.contentWidth;
	}

	/*? if <1.21.4 {*/
	/*@Override
	protected int getScrollbarPosition() {
		// The hit test compares absolute screen coordinates, so the anchor has to be absolute too.
		return this.getRowLeft() + this.getRowWidth() + 4;
	}
	*//*?}*/

	/** Width of a group row's square Files icon button; the screen uses the same value so row labels stop short of the button. */
	public static int filesButtonWidth() {
		return ActionAreaLayout.BUTTON_HEIGHT;
	}

	/** Vanilla renders the first row this far below the list's top edge; the window is shifted up so rows land where the caller asked. */
	/*? if >=1.21.10 {*/
	private static final int VANILLA_ROW_INSET = 2;
	/*?} else {*/
	/*private static final int VANILLA_ROW_INSET = 4;
	*//*?}*/
	private final int contentWidth;

	public GroupSelectionList(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<Item> items, Consumer<Item> onToggle, Consumer<Item> onInspect) {
		/*? if <1.20.3 {*/
		/*super(client, width, height, top - VANILLA_ROW_INSET, bottom, ROW_HEIGHT);
		*//*?} else {*/
		super(client, width, Math.max(ROW_HEIGHT, bottom - top) + VANILLA_ROW_INSET, top - VANILLA_ROW_INSET, ROW_HEIGHT);
		/*?}*/
		this.contentWidth = Math.max(1, contentWidth);
		this.centerListVertically = false;
		// The only chrome this list carries itself: it is a ContainerObjectSelectionList, so it cannot sit on the shared
		// ChromelessList base the other lists use. Its row window and scroll contract stay in sync with that base.
		/*? if <1.21.1 {*/
		/*this.setRenderBackground(false);
		this.setRenderTopAndBottom(false);
		*//*?}*/
		/*? if <1.20.4 {*/
		/*this.setRenderSelection(false);
		*//*?}*/
		Consumer<Item> toggle = Objects.requireNonNull(onToggle, "onToggle");
		Consumer<Item> inspect = Objects.requireNonNull(onInspect, "onInspect");
		for (Item item : Objects.requireNonNull(items, "items")) this.addEntry(new Entry(item, toggle, inspect));
	}

	/*? if >=1.21.10 {*/
	@Override
	public int maxScrollAmount() {
		// Vanilla pads contentHeight by 4 while its rows only start 2px down; with the window shifted onto the rows, the scroll range is exactly the overflow.
		return Math.max(0, this.contentHeight() - this.height - (4 - VANILLA_ROW_INSET));
	}

	/*?}*/

	@Override
	public void revealRow(int index) {
		Entry entry = this.children().get(index);
		/*? if >=1.21.9 {*/
		this.scrollToEntry(entry);
		/*?} else {*/
		/*this.ensureVisible(entry);
		*//*?}*/
		entry.layoutRow(this.getRowLeft(), this.getRowTop(index), this.getRowWidth());
	}

	@Override
	public RowView rowView(int index) {
		Item item = this.children().get(index).item();
		String text = item.counter().isBlank() ? item.label().getString() : item.label().getString() + " " + item.counter();
		return new RowView(text, item.canToggle(), item.kind() == Kind.CAPTION ? null : item.selected(), item.partial());
	}

	public int rowCount() {
		return this.children().size();
	}

	public int rowLeft() {
		return this.getRowLeft();
	}

	public int rowTop(int index) {
		return this.getRowTop(index);
	}

	public int rowWidth() {
		return this.contentWidth;
	}

	public int rowHeight() {
		return ROW_HEIGHT;
	}

	public record Item(Kind kind, String id, Component label, Component tooltip, boolean selected, boolean canToggle, boolean partial, String counter) {
		public Item {
			Objects.requireNonNull(kind, "kind");
			id = id == null ? "" : id;
			label = Objects.requireNonNull(label, "label");
			counter = counter == null ? "" : counter;
		}
	}

	public enum Kind {
		CAPTION,
		HEADER,
		GROUP
	}

	/** Group rows step right of their category header; the same step the change browser uses for nested rows. */
	private static final int CHILD_INDENT = 12;

	private static CheckboxWidget.State state(Item item) {
		return item.partial() ? CheckboxWidget.State.PARTIAL : item.selected() ? CheckboxWidget.State.CHECKED : CheckboxWidget.State.UNCHECKED;
	}

	public final class Entry extends ContainerObjectSelectionList.Entry<Entry> {
		private static final int TEXT_MARGIN = 6;
		private final Item item;
		private final AbstractWidget row;
		private final AbstractWidget filesButton;
		private final List<AbstractWidget> children;

		private Entry(Item item, Consumer<Item> onToggle, Consumer<Item> onInspect) {
			this.item = item;
			int rowWidth = GroupSelectionList.this.getRowWidth();
			if (item.kind() == Kind.CAPTION) {
				// The plain "General" section caption is a label, not a control: there is nothing to toggle.
				this.row = null;
				this.filesButton = null;
			} else {
				int indent = item.kind() == Kind.GROUP ? CHILD_INDENT : 0;
				int filesWidth = item.kind() == Kind.GROUP ? filesButtonWidth() : 0;
				int mainWidth = item.kind() == Kind.GROUP ? Math.max(1, rowWidth - indent - filesWidth - ActionAreaLayout.SEAM) : rowWidth;
				AbstractWidget checkbox = item.kind() == Kind.HEADER
						? new CategoryHeaderRow(minecraft.font, 0, 0, mainWidth, item.label(), state(item), item.counter(), () -> onToggle.accept(item))
						: new CheckboxWidget(minecraft.font, 0, 0, mainWidth, item.label(), item.selected(), value -> {
							if (value != item.selected()) onToggle.accept(item);
						});
				// Locked rows and inert headers still show their state, but the box is dead: the resolution owns it.
				checkbox.active = item.canToggle();
				if (item.tooltip() != null) VersionedScreen.setTooltip(checkbox, item.tooltip());
				this.row = checkbox;
				if (item.kind() == Kind.GROUP) {
					// The narration keeps the "Files" label so screen readers and the tooling bridge still name the action.
					Button files = VersionedScreen.iconButtonWidget(0, 0, filesWidth, 16, button -> onInspect.accept(item), "folder", filesLabel());
					if (item.tooltip() != null) VersionedScreen.setTooltip(files, item.tooltip());
					this.filesButton = files;
				} else {
					this.filesButton = null;
				}
			}
			this.children = this.row == null ? List.of() : this.filesButton == null ? List.of(this.row) : List.of(this.row, this.filesButton);
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
			layoutRow(this.getX(), this.getY(), this.getWidth());
			versionedRender(new VersionedMatrices(guiGraphics), this.getContentX(), this.getContentY(), this.getContentWidth(), mouseX, mouseY, tickDelta);
		}
		/*?} elif >= 1.21.9 {*/
		/*@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			layoutRow(this.getX(), this.getY(), GroupSelectionList.this.getRowWidth());
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
			layoutRow(x, y, entryWidth);
			versionedRender(versionedMatrices, x, y, entryWidth, mouseX, mouseY, tickDelta);
		}
		*//*?}*/

		private void versionedRender(VersionedMatrices matrices, int x, int y, int entryWidth, int mouseX, int mouseY, float tickDelta) {
			if (row == null) {
				Component label = item.label();
				VersionedScreen.drawTextWithShadow(matrices, minecraft.font, label instanceof MutableComponent mutable ? mutable : VersionedText.literal(label.getString()), x + TEXT_MARGIN, y + 7, TextColors.WHITE);
				return;
			}
			/*? if >=26.1 {*/
			row.extractRenderState(matrices.getContext(), mouseX, mouseY, tickDelta);
			if (filesButton != null) filesButton.extractRenderState(matrices.getContext(), mouseX, mouseY, tickDelta);
			/*?} else {*/
			/*row.render(matrices.getContext(), mouseX, mouseY, tickDelta);
			if (filesButton != null) filesButton.render(matrices.getContext(), mouseX, mouseY, tickDelta);
			*//*?}*/
		}

		/** Places the row and its controls at the given origin; also used at reveal time so a just-scrolled row is clickable before the next render. */
		private void layoutRow(int x, int y, int entryWidth) {
			/*? if >=1.21.9 {*/
			this.setX(x);
			this.setY(y);
			this.setWidth(entryWidth);
			this.setHeight(ROW_HEIGHT);
			/*?}*/
			if (row == null) return;
			positionWidget(row, x + (item.kind() == Kind.GROUP ? CHILD_INDENT : 0), y);
			if (filesButton != null) positionWidget(filesButton, x + GroupSelectionList.this.getRowWidth() - filesButton.getWidth(), y);
		}

		private static void positionWidget(AbstractWidget widget, int x, int y) {
			/*? if >=1.19.4 {*/
			widget.setX(x);
			widget.setY(y);
			/*?} else {*/
			/*widget.x = x;
			widget.y = y;
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
