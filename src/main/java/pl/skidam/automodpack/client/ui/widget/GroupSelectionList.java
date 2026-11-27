package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScissor;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/*? if >=1.21.10 {*/
import net.minecraft.client.input.InputWithModifiers;
/*?}*/
/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/** Scrolling group selection cards and section headers. */
public final class GroupSelectionList extends ContainerObjectSelectionList<GroupSelectionList.Entry> implements RowViewport {
	private static final int ROW_HEIGHT = 34;
	private static final int CARD_HEIGHT = 32;
	private static final int CHILD_INDENT = 8;
	private static final int RADIO_SIZE = 13;
	private static final int CARD_FILL_SELECTED = 0x26000000;
	private static final int CARD_FILL_UNSELECTED = 0x14000000;
	private static final int CARD_BORDER = 0x28FFFFFF;
	private static final int HEADER_DIVIDER = 0x30FFFFFF;
	private static final int RADIO_BORDER = 0xFF909090;
	private static final int RADIO_BORDER_ACTIVE = 0xFFFFFFFF;
	private static final int RADIO_BORDER_DISABLED = 0xFF606060;
	private static final int RADIO_INNER_BG = 0xFF141414;

	/** Marks the primary card control inside a row so AutoTestBridge avoids emitting duplicate elements alongside RowView. */
	public interface RowPrimaryControl {}

	private static Component filesLabel() {
		return VersionedText.text("automodpack.selection.groupFiles");
	}

	@Override
	public int getRowWidth() {
		return this.contentWidth;
	}

	/*? if <1.21.4 {*/
	/*@Override
	protected int getScrollbarPosition() {
		return ListChrome.scrollbarPosition(this.getRowLeft(), this.getRowWidth());
	}
	*//*?}*/

	public static int filesButtonWidth() {
		return ActionAreaLayout.BUTTON_HEIGHT;
	}

	public static int cardTextWidth(int rowWidth) {
		int cardWidth = Math.max(1, rowWidth - CHILD_INDENT - filesButtonWidth() - ActionAreaLayout.SEAM);
		return Math.max(1, cardWidth - 32);
	}

	private final int contentWidth;

	public GroupSelectionList(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<Item> items, Consumer<Item> onToggle, Consumer<Item> onInspect) {
		/*? if <1.20.3 {*/
		/*super(client, width, height, ListChrome.windowTop(top), bottom, ROW_HEIGHT);
		this.setLeftPos(0);
		*//*?} else {*/
		super(client, width, ListChrome.listHeight(ROW_HEIGHT, top, bottom), ListChrome.windowTop(top), ROW_HEIGHT);
		/*?}*/
		this.contentWidth = Math.max(1, contentWidth);
		this.centerListVertically = false;
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
		this.syncLayout();
	}

	/*? if >=1.21.10 {*/
	@Override
	public int maxScrollAmount() {
		return ListChrome.scrollRange(this.contentHeight(), this.height);
	}

	/*?}*/
	/*? if >=26.1 {*/
	@Override
	protected void extractListBackground(GuiGraphicsExtractor guiGraphics) {
		ListChrome.drawBackdrop(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor guiGraphics) {
		ListChrome.drawSeparators(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}
	/*?} elif >=1.21.10 {*/
	/*@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {
		ListChrome.drawBackdrop(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {
		ListChrome.drawSeparators(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}
	*//*?} elif >=1.21.1 {*/
	/*@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {
		ListChrome.drawBackdrop(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {
		ListChrome.drawSeparators(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected void renderSelection(GuiGraphics guiGraphics, int y, int entryWidth, int entryHeight, int outlineColor, int innerColor) {}
	*//*?} elif >=1.20.4 {*/
	/*@Override
	protected void renderSelection(GuiGraphics guiGraphics, int y, int entryWidth, int entryHeight, int outlineColor, int innerColor) {}
	*//*?}*/

	/*? if <1.19.4 {*/
	/*@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		VersionedMatrices chrome = new VersionedMatrices();
		ListChrome.drawBackdrop(chrome, x0, y0, x1, y1);
		ListChrome.drawSeparators(chrome, x0, y0, x1, y1);
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(matrices, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?} elif <1.20.3 {*/
	/*@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		VersionedMatrices chrome = new VersionedMatrices(graphics);
		ListChrome.drawBackdrop(chrome, x0, y0, x1, y1);
		ListChrome.drawSeparators(chrome, x0, y0, x1, y1);
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(graphics, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?}*/

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
	public void syncLayout() {
		int left = this.getRowLeft();
		int width = this.getRowWidth();
		List<Entry> entries = this.children();
		for (int i = 0; i < entries.size(); i++) entries.get(i).layoutRow(left, this.getRowTop(i), width);
	}

	@Override
	public int viewportTop() {
		/*? if <1.20.3 {*/
		/*return this.y0;
		*//*?} else {*/
		return this.getY();
		/*?}*/
	}

	@Override
	public int viewportBottom() {
		/*? if <1.20.3 {*/
		/*return this.y1;
		*//*?} else {*/
		return this.getY() + this.getHeight();
		/*?}*/
	}

	public double preservedScrollAmount() {
		/*? if >=1.21.4 {*/
		return this.scrollAmount();
		/*?} else {*/
		/*return this.getScrollAmount();
		*//*?}*/
	}

	public void restoreScrollAmount(double amount) {
		this.setScrollAmount(amount);
		this.syncLayout();
	}

	@Override
	public RowView rowView(int index) {
		Item item = this.children().get(index).item();
		StringBuilder text = new StringBuilder(item.title().getString());
		String badge = item.badge().getString();
		if (!badge.isBlank()) text.append(' ').append(badge);
		String subtitle = item.subtitle().getString();
		if (!subtitle.isBlank()) text.append(" | ").append(subtitle);
		if (item.kind() == Kind.HEADER) return new RowView(text.toString(), !item.actionLabel().isBlank(), null, false);
		return new RowView(text.toString(), item.canToggle(), item.selected(), false);
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

	public record Item(
			Kind kind,
			String id,
			String categoryId,
			Component title,
			Component subtitle,
			Component badge,
			Component tooltip,
			boolean selected,
			boolean canToggle,
			boolean showFilesButton,
			String actionLabel,
			int accentColor) {
		public Item {
			Objects.requireNonNull(kind, "kind");
			id = id == null ? "" : id;
			categoryId = categoryId == null ? "" : categoryId;
			title = Objects.requireNonNull(title, "title");
			subtitle = subtitle == null ? VersionedText.literal("") : subtitle;
			badge = badge == null ? VersionedText.literal("") : badge;
			actionLabel = actionLabel == null ? "" : actionLabel;
		}
	}

	public enum Kind {
		HEADER,
		CHECKBOX_GROUP,
		RADIO_GROUP
	}

	public final class Entry extends ContainerObjectSelectionList.Entry<Entry> {
		private final Item item;
		private final AbstractWidget primaryControl;
		private final AbstractWidget actionButton;
		private final AbstractWidget filesButton;
		private final List<AbstractWidget> children;

		private Entry(Item item, Consumer<Item> onToggle, Consumer<Item> onInspect) {
			this.item = item;
			int rowWidth = GroupSelectionList.this.getRowWidth();
			if (item.kind() == Kind.HEADER) {
				this.primaryControl = null;
				this.filesButton = null;
				if (!item.actionLabel().isBlank()) {
					int buttonWidth = Math.max(44, minecraft.font.width(item.actionLabel()) + 14);
					Button action = VersionedScreen.buttonWidget(0, 0, buttonWidth, 16, VersionedText.literal(item.actionLabel()), button -> onToggle.accept(item));
					if (item.tooltip() != null) VersionedScreen.setTooltip(action, item.tooltip());
					this.actionButton = action;
					this.children = List.of(action);
				} else {
					this.actionButton = null;
					this.children = List.of();
				}
				return;
			}

			int filesWidth = filesButtonWidth();
			int cardWidth = Math.max(1, rowWidth - CHILD_INDENT - filesWidth - ActionAreaLayout.SEAM);
			GroupCardButton card = new GroupCardButton(minecraft.font, 0, 0, cardWidth, CARD_HEIGHT, item, () -> onToggle.accept(item));
			card.active = item.canToggle();
			if (item.tooltip() != null) VersionedScreen.setTooltip(card, item.tooltip());
			this.primaryControl = card;
			this.actionButton = null;
			if (item.showFilesButton()) {
				Button files = VersionedScreen.iconButtonWidget(0, 0, filesWidth, 16, button -> onInspect.accept(item), "folder", filesLabel());
				VersionedScreen.setTooltip(files, filesLabel());
				this.filesButton = files;
				this.children = List.of(card, files);
			} else {
				this.filesButton = null;
				this.children = List.of(card);
			}
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
			layoutRow(this.getX(), this.getY(), GroupSelectionList.this.getRowWidth());
			versionedRender(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), GroupSelectionList.this.getRowWidth(), mouseX, mouseY, tickDelta);
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
			if (item.kind() == Kind.HEADER) {
				renderHeader(matrices, x, y, entryWidth);
				if (actionButton != null) renderWidget(actionButton, matrices, mouseX, mouseY, tickDelta);
				return;
			}
			if (primaryControl != null) renderWidget(primaryControl, matrices, mouseX, mouseY, tickDelta);
			if (filesButton != null) renderWidget(filesButton, matrices, mouseX, mouseY, tickDelta);
		}

		private void renderHeader(VersionedMatrices matrices, int x, int y, int entryWidth) {
			int rightReserve = actionButton == null ? 0 : actionButton.getWidth() + ActionAreaLayout.SEAM;
			int maxTextWidth = Math.max(1, entryWidth - rightReserve - 4);
			Font font = minecraft.font;
			boolean hasSubtitle = !item.subtitle().getString().isBlank();
			int titleY = hasSubtitle ? y + 6 : y + ROW_HEIGHT - font.lineHeight - 7;
			MutableComponent title = item.title() instanceof MutableComponent mutable
					? VersionedText.literal(VersionedScreen.truncateToWidth(font, mutable.getString(), maxTextWidth)).withStyle(mutable.getStyle())
					: VersionedText.literal(VersionedScreen.truncateToWidth(font, item.title().getString(), maxTextWidth));
			VersionedScreen.drawTextWithShadow(matrices, font, title, x + 2, titleY, TextColors.WHITE);
			if (hasSubtitle) {
				String sub = VersionedScreen.truncateToWidth(font, item.subtitle().getString(), maxTextWidth);
				VersionedScreen.drawTextWithShadow(matrices, font, VersionedText.literal(sub), x + 2, y + 18, TextColors.LIGHT_GRAY);
			}
			matrices.fill(x, y + ROW_HEIGHT - 2, x + entryWidth, y + ROW_HEIGHT - 1, HEADER_DIVIDER);
		}

		private void renderWidget(AbstractWidget widget, VersionedMatrices matrices, int mouseX, int mouseY, float tickDelta) {
			/*? if >=26.1 {*/
			widget.extractRenderState(matrices.getContext(), mouseX, mouseY, tickDelta);
			/*?} else {*/
			/*widget.render(matrices.getContext(), mouseX, mouseY, tickDelta);
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
			int rowWidth = GroupSelectionList.this.getRowWidth();
			if (actionButton != null) positionWidget(actionButton, x + rowWidth - actionButton.getWidth(), y + (ROW_HEIGHT - actionButton.getHeight()) / 2);
			if (primaryControl != null) positionWidget(primaryControl, x + CHILD_INDENT, y);
			if (filesButton != null) positionWidget(filesButton, x + rowWidth - filesButton.getWidth(), y + (CARD_HEIGHT - filesButton.getHeight()) / 2);
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
	}

	private static final class GroupCardButton extends AbstractButton implements RowPrimaryControl {
		private final Font font;
		private final Item item;
		private final Runnable onPressAction;

		private GroupCardButton(Font font, int x, int y, int width, int height, Item item, Runnable onPressAction) {
			super(x, y, Math.max(1, width), Math.max(1, height), item.title());
			this.font = font;
			this.item = item;
			this.onPressAction = onPressAction;
		}

		/*? if >=1.21.10 {*/
		@Override
		public void onPress(InputWithModifiers input) {
			onPressAction.run();
		}
		/*?} else {*/
		/*@Override
		public void onPress() {
			onPressAction.run();
		}
		*//*?}*/

		/*? if <1.19.4 {*/
		/*@Override
		public void updateNarration(NarrationElementOutput output) {
			output.add(NarratedElementType.TITLE, getMessage());
		}
		*//*?} else {*/
		@Override
		public void updateWidgetNarration(NarrationElementOutput output) {
			output.add(NarratedElementType.TITLE, getMessage());
		}
		/*?}*/

		/*? if >=26.1 {*/
		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
			renderCard(new VersionedMatrices(graphics), mouseX, mouseY);
		}
		/*?} elif >=1.21.11 {*/
		/*@Override
		public void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
			renderCard(new VersionedMatrices(graphics), mouseX, mouseY);
		}
		*//*?} elif >=1.20 {*/
		/*@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
			renderCard(new VersionedMatrices(graphics), mouseX, mouseY);
		}
		*//*?} else {*/
		/*@Override
		public void renderButton(PoseStack matrices, int mouseX, int mouseY, float delta) {
			renderCard(new VersionedMatrices(), mouseX, mouseY);
		}
		*//*?}*/

		private void renderCard(VersionedMatrices matrices, int mouseX, int mouseY) {
			int left = cardLeft();
			int top = cardTop();
			int width = getWidth();
			int height = getHeight();
			boolean hovered = mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + height;

			matrices.fill(left, top, left + width, top + height, item.selected() ? CARD_FILL_SELECTED : CARD_FILL_UNSELECTED);
			if (hovered && this.active) ListChrome.drawHover(matrices, left, top, width, height);
			matrices.fill(left, top + height - 1, left + width, top + height, CARD_BORDER);
			if (item.accentColor() != 0) matrices.fill(left, top, left + 2, top + height, item.accentColor());

			if (item.kind() == Kind.RADIO_GROUP) {
				drawRadioCircle(matrices, left + 6, top + (height - RADIO_SIZE) / 2, item.selected(), this.active, hovered);
			} else {
				CheckboxWidget.drawBox(matrices, left + 4, top + (height - CheckboxWidget.BOX_SIZE) / 2,
						item.selected() ? CheckboxWidget.State.CHECKED : CheckboxWidget.State.UNCHECKED);
			}

			int textLeft = left + 26;
			int textRight = left + width - 6;
			String badgeText = item.badge().getString();
			int badgeWidth = badgeText.isBlank() ? 0 : font.width(badgeText);
			int titleMaxWidth = Math.max(1, textRight - textLeft - (badgeWidth > 0 ? badgeWidth + 6 : 0));
			String subtitleText = item.subtitle().getString();
			boolean hasSubtitle = !subtitleText.isBlank();

			int titleY = hasSubtitle ? top + 5 : top + (height - font.lineHeight) / 2;
			MutableComponent styledTitle = styledCopy(item.title(), VersionedScreen.truncateToWidth(font, item.title().getString(), titleMaxWidth));
			VersionedScreen.drawTextWithShadow(matrices, font, styledTitle, textLeft, titleY, TextColors.WHITE);
			if (badgeWidth > 0) {
				MutableComponent styledBadge = styledCopy(item.badge(), badgeText);
				VersionedScreen.drawTextWithShadow(matrices, font, styledBadge, textRight - badgeWidth, titleY, TextColors.LIGHT_GRAY);
			}
			if (hasSubtitle) {
				MutableComponent styledSub = styledCopy(item.subtitle(), VersionedScreen.truncateToWidth(font, subtitleText, Math.max(1, textRight - textLeft)));
				VersionedScreen.drawTextWithShadow(matrices, font, styledSub, textLeft, top + 17, TextColors.LIGHT_GRAY);
			}
		}

		private static MutableComponent styledCopy(Component original, String truncatedText) {
			MutableComponent copy = VersionedText.literal(truncatedText);
			return original instanceof MutableComponent mutable ? copy.withStyle(mutable.getStyle()) : copy;
		}

		private static void drawRadioCircle(VersionedMatrices matrices, int x, int y, boolean selected, boolean active, boolean hovered) {
			int border = !active ? RADIO_BORDER_DISABLED : selected || hovered ? RADIO_BORDER_ACTIVE : RADIO_BORDER;
			// 13x13 crisp circular outline via row spans
			int[] insets = { 4, 2, 1, 1, 0, 0, 0, 0, 0, 1, 1, 2, 4 };
			for (int row = 0; row < RADIO_SIZE; row++) {
				int inset = insets[row];
				matrices.fill(x + inset, y + row, x + RADIO_SIZE - inset, y + row + 1, border);
				int innerInset = row == 0 || row == RADIO_SIZE - 1 ? RADIO_SIZE : insets[row] + 1;
				if (innerInset < RADIO_SIZE - innerInset) {
					matrices.fill(x + innerInset, y + row, x + RADIO_SIZE - innerInset, y + row + 1, RADIO_INNER_BG);
				}
			}
			if (selected) {
				int dotColor = active ? RADIO_BORDER_ACTIVE : RADIO_BORDER;
				matrices.fill(x + 4, y + 3, x + 9, y + 10, dotColor);
				matrices.fill(x + 3, y + 4, x + 10, y + 9, dotColor);
			}
		}

		private int cardLeft() {
			/*? if >=1.19.4 {*/
			return getX();
			/*?} else {*/
			/*return x;
			*//*?}*/
		}

		private int cardTop() {
			/*? if >=1.19.4 {*/
			return getY();
			/*?} else {*/
			/*return y;
			*//*?}*/
		}
	}
}
