package pl.skidam.automodpack.client.ui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;

/*? if <1.20 {*/
/*import net.minecraft.client.util.math.MatrixStack;
*//*?} else {*/
import net.minecraft.client.gui.DrawContext;
import pl.skidam.automodpack.mixin.core.DrawContextAccessor;
/*?}*/

public class ListEntry extends AlwaysSelectedEntryListWidget.Entry<ListEntry> {

	protected final MinecraftClient client;
	private final MutableText text;
	private final String mainPageUrl;
	private final boolean bigFont;

	public ListEntry(MutableText text, String mainPageUrl, boolean bigFont, MinecraftClient client) {
		this.text = text;
		this.mainPageUrl = mainPageUrl;
		this.client = client;
		this.bigFont = bigFont;
	}

	public ListEntry(MutableText text, boolean bigFont, MinecraftClient client) {
		this.text = text;
		this.mainPageUrl = null;
		this.client = client;
		this.bigFont = bigFont;
	}

	/*? if >=1.17 {*/
	@Override
	public Text getNarration() {
		return text;
	}
	/*?}*/

	@Override
	/*? if <1.20 {*/
    /*public void render(MatrixStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
        VersionedMatrices versionedMatrices = new VersionedMatrices();
    *//*?} else {*/
	public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
		VersionedMatrices versionedMatrices = new VersionedMatrices(this.client, ((DrawContextAccessor) context).vertexConsumers());
	/*?}*/
		versionedMatrices.push();

		int centeredX = x + entryWidth / 2;
		int centeredY = y + entryHeight / 2;
		if (bigFont) {
			float scale = 1.5f;
			versionedMatrices.scale(scale, scale, scale);
			centeredX = (int) (centeredX / scale);
			centeredY = (int) (centeredY - (float) 10 / 2 * scale);
		} else {
			centeredY = centeredY - 10 / 2;
		}

		VersionedScreen.drawCenteredTextWithShadow(versionedMatrices, client.textRenderer, text, centeredX, centeredY, 16777215);

		// if (mainPageUrls != null) {
		//     int badgeX = x - 42;
		//     int badgeY = y + 2;
		//     if (mainPageUrls.contains("modrinth")) {
		//         Badge.renderModrinthBadge(versionedMatrices, badgeX, badgeY);
		//     } else if (mainPageUrls.contains("curseforge")) {
		//         Badge.renderCurseForgeBadge(versionedMatrices, badgeX, badgeY);
		//     }
		// }

		versionedMatrices.pop();
	}

	public MutableText getText() {
		return this.text;
	}

	public String getMainPageUrl() {
		return mainPageUrl;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int delta) {
		return !bigFont;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return false;
	}
}
