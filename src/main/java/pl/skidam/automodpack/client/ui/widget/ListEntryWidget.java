package pl.skidam.automodpack.client.ui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

import java.util.Map;

/*? if <1.20 {*/
/*import net.minecraft.client.util.math.MatrixStack;
*//*?} elif <1.20.3 {*/
/*import net.minecraft.client.gui.DrawContext;
*//*?}*/

public class ListEntryWidget extends AlwaysSelectedEntryListWidget<ListEntry> {

	private boolean scrolling;

	public ListEntryWidget(Map<String, String> changelogs, MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
		/*? if <1.20.3 {*/
        /*super(client, width, height, top, bottom, itemHeight);
        *//*?} else {*/
		super(client, width, height - 90, top, itemHeight);
		/*?}*/
		this.centerListVertically = true;

		this.clearEntries();

		if (changelogs == null || changelogs.isEmpty()) {
			ListEntry entry = new ListEntry(VersionedText.literal("No changelogs found").formatted(Formatting.BOLD), true, this.client);
			this.addEntry(entry);
			return;
		}

		for (Map.Entry<String, String> changelog : changelogs.entrySet()) {
			String textString = changelog.getKey();
			String mainPageUrl = changelog.getValue();

			MutableText text = VersionedText.literal(textString);

			if (textString.startsWith("+")) {
				text = text.formatted(Formatting.GREEN);
			} else if (textString.startsWith("-")) {
				text = text.formatted(Formatting.RED);
			}

			ListEntry entry = new ListEntry(text, mainPageUrl, false, this.client);
			this.addEntry(entry);
		}
	}

	/*? if <=1.20.2 {*/
    /*public void render(/^? if <1.20 {^/  /^MatrixStack  ^//^?} else {^/ DrawContext /^?}^/  matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
    }
    *//*?}*/

	/*? if >1.21.3 {*/
	public double getScrollAmount() {
		return this.getScrollY();
	}
	/*?}*/

	public final ListEntry getEntryAtPos(double x, double y) {
		int int_5 = MathHelper.floor(y - (double) getTop()) - this.headerHeight + (int) this.getScrollAmount() - 4;
		int index = int_5 / this.itemHeight;
		return x < (double) this.getScrollbarX() && x >= (double) getRowLeft() && x <= (double) (getRowLeft() + getRowWidth()) && index >= 0 && int_5 >= 0 && index < this.getEntryCount() ? this.children().get(index) : null;
	}

	public int getTop() {
		/*? if <1.20.3 {*/
        /*return this.top;
        *//*?} else {*/
		return this.getY();
		/*?}*/
	}

	/*? if <=1.21.3 {*/
	/*@Override
	protected void updateScrollingState(double mouseX, double mouseY, int button) {
		super.updateScrollingState(mouseX, mouseY, button);
		this.scrolling = button == 0 && mouseX >= (double) this.getScrollbarX() && mouseX < (double) (this.getScrollbarX() + 6);
	}
	*//*?}*/

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		/*? if <=1.21.3 {*/
		/*this.updateScrollingState(mouseX, mouseY, button);
		*//*?}*/
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

	@Override
	public void setSelected(ListEntry entry) {
		super.setSelected(entry);
		if (entry != null) {
			this.centerScrollOn(entry);
		}
	}

	protected int getScrollbarX() {
		return this.width - 6;
	}

	@Override
	public int getRowWidth() {
		return super.getRowWidth() + 120;
	}
}
