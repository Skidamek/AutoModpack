package pl.skidam.automodpack.client.ui.widget;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;

import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

/*? if >= 1.21.9 {*/
import net.minecraft.client.input.MouseButtonEvent;
/*?}*/

/*? if <1.20 {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?} elif <1.20.3 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?}*/

public class ListEntryWidget extends ObjectSelectionList<ListEntry> {

	private boolean scrolling;

	public ListEntryWidget(Map<String, String> changelogs, Minecraft client, int width, int height, int top, int bottom, int itemHeight) {
		/*? if <1.20.3 {*/
        /*super(client, width, height, top, bottom, itemHeight);
        *//*?} else {*/
		super(client, width, height - 90, top, itemHeight);
		/*?}*/
		this.centerListVertically = true;

		this.clearEntries();

		if (changelogs == null || changelogs.isEmpty()) {
			ListEntry entry = new ListEntry(VersionedText.literal("No changelogs found").withStyle(ChatFormatting.BOLD), true, this.minecraft);
			this.addEntry(entry);
			return;
		}

		for (Map.Entry<String, String> changelog : changelogs.entrySet()) {
			String textString = changelog.getKey();
			String mainPageUrl = changelog.getValue();

			MutableComponent text = VersionedText.literal(textString);

			if (textString.startsWith("+")) {
				text = text.withStyle(ChatFormatting.GREEN);
			} else if (textString.startsWith("-")) {
				text = text.withStyle(ChatFormatting.RED);
			}

			ListEntry entry = new ListEntry(text, mainPageUrl, false, this.minecraft);
			this.addEntry(entry);
		}
	}

	/*? if <=1.20.2 {*/
    /*public void render(/^? if <1.20 {^/  /^PoseStack  ^//^?} else {^/ GuiGraphics /^?}^/  matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
    }
    *//*?}*/

	/*? if >=1.21.4 {*/
	public double getScrollAmount() {
		return this.scrollAmount();
	}
	/*?}*/

	public final ListEntry getEntryAtPos(double x, double y) {
        /*? if >= 1.21.9 {*/
        int int_5 = Mth.floor(y - (double) getTop()) + (int) this.getScrollAmount() - 4;
        int index = int_5 / this.defaultEntryHeight; // TODO: check if this is correct, not sure where itemHeight went
        /*?} else {*/
		/*int int_5 = Mth.floor(y - (double) getTop()) - this.headerHeight + (int) this.getScrollAmount() - 4;
		int index = int_5 / this.itemHeight;
        *//*?}*/
		return x < (double) this.getScrollbarPosition() && x >= (double) getRowLeft() && x <= (double) (getRowLeft() + getRowWidth()) && index >= 0 && int_5 >= 0 && index < this.getItemCount() ? this.children().get(index) : null;
	}

	public int getTop() {
		/*? if <1.20.3 {*/
        /*return this.y0;
        *//*?} else {*/
		return this.getY();
		/*?}*/
	}

	/*? if <=1.21.3 {*/
	/*@Override
	protected void updateScrollingState(double mouseX, double mouseY, int button) {
		super.updateScrollingState(mouseX, mouseY, button);
		this.scrolling = button == 0 && mouseX >= (double) this.getScrollbarPosition() && mouseX < (double) (this.getScrollbarPosition() + 6);
	}
	*//*?}*/

    /*? if >= 1.21.9 {*/
    @Override
    public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
        if (!this.isMouseOver(mouseButtonEvent.x(), mouseButtonEvent.y())) {
            return false;
        } else {
            ListEntry entry = this.getEntryAtPos(mouseButtonEvent.x(), mouseButtonEvent.y());
            if (entry != null) {
                if (entry.mouseClicked(mouseButtonEvent, bl)) {
                    this.setFocused(entry);
                    this.setSelected(entry);
                    this.setDragging(true);
                    return true;
                }
            }

            return this.scrolling;
        }
    }
    /*?} else {*/
	/*@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		/^? if <=1.21.3 {^/
		/^this.updateScrollingState(mouseX, mouseY, button);
		^//^?}^/
		if (!this.isMouseOver(mouseX, mouseY)) {
			return false;
		} else {
			ListEntry entry = this.getEntryAtPos(mouseX, mouseY);
			if (entry != null) {
				if (entry.mouseClicked(mouseX, mouseY, button)) {
					this.setFocused(entry);
					this.setSelected(entry);
					this.setDragging(true);
					return true;
				}
			}

			return this.scrolling;
		}
	}
    *//*?}*/

	@Override
	public void setSelected(ListEntry entry) {
		super.setSelected(entry);
		if (entry != null) {
			this.centerScrollOn(entry);
		}
	}

	protected int getScrollbarPosition() {
		return this.width - 6;
	}

	@Override
	public int getRowWidth() {
		return super.getRowWidth() + 120;
	}
}
