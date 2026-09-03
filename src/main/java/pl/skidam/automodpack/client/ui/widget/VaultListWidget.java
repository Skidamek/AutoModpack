package pl.skidam.automodpack.client.ui.widget;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.update.PreservationVault;

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

/** A native selection list of the files a modpack preserved, one claim per two-line row. */
public final class VaultListWidget extends ObjectSelectionList<VaultListWidget.Entry> implements RowViewport {
	private static final int ROW_HEIGHT = 24;
	private static final int TEXT_MARGIN = 6;
	private final int contentWidth;
	private final Consumer<PreservationVault.Claim> claimPicked;

	public VaultListWidget(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<PreservationVault.Claim> claims, Map<String, String> packNames, Consumer<PreservationVault.Claim> claimPicked) {
		/*? if <1.20.3 {*/
		/*super(client, width, height, top, bottom, ROW_HEIGHT);
		*//*?} else {*/
		super(client, width, Math.max(ROW_HEIGHT, bottom - top), top, ROW_HEIGHT);
		/*?}*/
		this.contentWidth = Math.max(1, contentWidth);
		this.centerListVertically = false;
		this.claimPicked = Objects.requireNonNull(claimPicked, "claim pick");
		Map<String, String> names = Map.copyOf(packNames == null ? Map.of() : packNames);
		for (PreservationVault.Claim claim : Objects.requireNonNull(claims, "claims")) this.addEntry(new Entry(claim, names));
	}

	/** The claim behind the highlighted row, or null when the list selection is empty. */
	public PreservationVault.Claim selectedClaim() {
		Entry selected = this.getSelected();
		return selected == null ? null : selected.claim();
	}

	public void selectClaim(String claimId) {
		if (claimId == null || claimId.isBlank()) {
			this.setSelected(null);
			return;
		}
		for (Entry entry : this.children()) {
			if (entry.claim().claimId().equals(claimId)) {
				this.setSelected(entry);
				/*? if >=1.21.9 {*/
				this.scrollToEntry(entry);
				/*?} else {*/
				/*this.ensureVisible(entry);
				*//*?}*/
				return;
			}
		}
	}

	@Override
	public List<? extends ObjectSelectionList.Entry<?>> entries() {
		return this.children();
	}

	@Override
	public void revealRow(int index) {
		/*? if >=1.21.9 {*/
		this.scrollToEntry(this.children().get(index));
		/*?} else {*/
		/*this.ensureVisible(this.children().get(index));
		*//*?}*/
	}

	@Override
	public int rowLeft() {
		return this.getRowLeft();
	}

	@Override
	public int rowTop(int index) {
		return this.getRowTop(index);
	}

	@Override
	public int rowWidth() {
		return this.contentWidth;
	}

	@Override
	public int rowHeight() {
		return ROW_HEIGHT;
	}

	protected int getScrollbarPosition() {
		return Math.min(this.width - 6, this.width / 2 + this.getRowWidth() / 2 + 6);
	}

	public final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final PreservationVault.Claim claim;
		private final Map<String, String> packNames;

		private Entry(PreservationVault.Claim claim, Map<String, String> packNames) {
			this.claim = Objects.requireNonNull(claim, "preserved claim");
			this.packNames = packNames;
		}

		public PreservationVault.Claim claim() {
			return claim;
		}

		@Override
		public @NotNull Component getNarration() {
			return VersionedText.literal(fileName() + ", " + detail());
		}

		private String fileName() {
			if (claim.originalPath() == null || claim.originalPath().isBlank()) return "";
			Path path = Path.of(claim.originalPath().replace('\\', '/'));
			Path name = path.getFileName();
			return name == null ? claim.originalPath() : name.toString();
		}

		private String detail() {
			String packName = packNames.getOrDefault(claim.modpackId(), claim.modpackId());
			return packName + " | " + claim.originalPath() + " | " + reason() + " | " + UiFormat.formatInstant(claim.preservedAt());
		}

		private String reason() {
			return VersionedText.translatable("automodpack.vault.reason." + claim.reason().name().toLowerCase(Locale.ROOT)).getString();
		}

		/*? if >= 26.1 {*/
		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getContentX(), this.getContentY(), this.getContentWidth());
		}
		/*?} elif >= 1.21.9 {*/
		/*@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), VaultListWidget.this.getRowWidth());
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
			String size = UiFormat.formatSize(claim.size());
			int lineWidth = Math.max(1, entryWidth - TEXT_MARGIN * 2 - minecraft.font.width(size));
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, fileName(), lineWidth)).withStyle(ChatFormatting.WHITE), x + TEXT_MARGIN, y + 4, TextColors.WHITE);
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(size), x + entryWidth - TEXT_MARGIN - minecraft.font.width(size), y + 4, TextColors.WHITE);
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, detail(), Math.max(1, entryWidth - TEXT_MARGIN * 2))).withStyle(ChatFormatting.GRAY), x + TEXT_MARGIN, y + 14, TextColors.WHITE);
		}

		/*? if >= 1.21.9 {*/
		@Override
		public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
			activate(this);
			return true;
		}
		/*?} else {*/
		/*@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			activate(this);
			return true;
		}
		*//*?}*/

		/*? if < 1.21.9 {*/
		/*@Override
		public boolean mouseReleased(double mouseX, double mouseY, int button) {
			return false;
		}
		*//*?}*/
	}

	private void activate(Entry entry) {
		this.setSelected(entry);
		if (claimPicked != null) claimPicked.accept(entry.claim());
	}
}
