package pl.skidam.automodpack.client.ui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;

public class ListEntry extends ObjectSelectionList.Entry<ListEntry> {

	protected final Minecraft client;
	private final MutableComponent text;
	private final String mainPageUrl;
	private final boolean bigFont;

	public ListEntry(MutableComponent text, String mainPageUrl, boolean bigFont, Minecraft client) {
		this.text = text;
		this.mainPageUrl = mainPageUrl;
		this.client = client;
		this.bigFont = bigFont;
	}

	public ListEntry(MutableComponent text, boolean bigFont, Minecraft client) {
		this.text = text;
		this.mainPageUrl = null;
		this.client = client;
		this.bigFont = bigFont;
	}

	/*? if >=1.17 {*/
	@Override
	public Component getNarration() {
		return text;
	}
	/*?}*/

	@Override
	/*? if <1.20 {*/
    /*public void render(MatrixStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
		VersionedMatrices versionedMatrices = new VersionedMatrices();
    *//*?} else {*/
	public void render(GuiGraphics GuiGraphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
		VersionedMatrices versionedMatrices = new VersionedMatrices(GuiGraphics);
	/*?}*/
		versionedRender(versionedMatrices, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, tickDelta);
	}
	public void versionedRender(VersionedMatrices versionedMatrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
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

		VersionedScreen.drawCenteredTextWithShadow(versionedMatrices, client.font, text, centeredX, centeredY, TextColors.WHITE);

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

	public MutableComponent getText() {
		return this.text;
	}

	public String getMainPageUrl() {
		return mainPageUrl;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return !bigFont;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return false;
	}
}