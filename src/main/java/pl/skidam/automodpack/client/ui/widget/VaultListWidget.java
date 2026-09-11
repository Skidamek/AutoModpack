package pl.skidam.automodpack.client.ui.widget;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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

/** A native selection list of the files a modpack preserved, one claim per two-line row. */
public final class VaultListWidget extends ChromelessList<VaultListWidget.Entry> implements RowViewport {
	private static final int ROW_HEIGHT = 24;
	private static final int TEXT_MARGIN = 6;
	/** Marks the claim the restore and save-copy actions act on; the vanilla selection outline is stripped chrome. */
	private static final int SELECTED_COLOR = 0x40FFFFFF;
	private final Consumer<PreservationVault.Claim> claimPicked;

	public VaultListWidget(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<PreservationVault.Claim> claims, Map<String, String> packNames, Consumer<PreservationVault.Claim> claimPicked) {
		super(client, width, height, 0, top, bottom, contentWidth, ROW_HEIGHT);
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
	protected void pinEntry(VaultListWidget.Entry entry, int index) {
		entry.layoutEntry(getRowLeft(), getRowTop(index), getRowWidth());
	}

	@Override
	public RowView rowView(int index) {
		return new RowView(this.children().get(index).getNarration().getString(), true, null, false);
	}

	public final class Entry extends Row<Entry> {
		private final PreservationVault.Claim claim;
		private final Map<String, String> packNames;

		private Entry(PreservationVault.Claim claim, Map<String, String> packNames) {
			this.claim = Objects.requireNonNull(claim, "preserved claim");
			this.packNames = packNames;
		}

		public PreservationVault.Claim claim() {
			return claim;
		}

		/** Pins the row's hit-test rectangle to its live position, so tooling can click a row that has not rendered yet. */
		private void layoutEntry(int x, int y, int width) {
			/*? if >=1.21.9 {*/
			this.setX(x);
			this.setY(y);
			this.setWidth(width);
			this.setHeight(ROW_HEIGHT);
			/*?}*/
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

		@Override
		protected void versionedRender(VersionedMatrices matrices, int x, int y, int width, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			if (getSelected() == this) matrices.fill(x, y, x + width, y + ROW_HEIGHT, SELECTED_COLOR);
			String size = UiFormat.formatSize(claim.size());
			int lineWidth = Math.max(1, width - TEXT_MARGIN * 2 - minecraft.font.width(size));
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, fileName(), lineWidth)).withStyle(ChatFormatting.WHITE), x + TEXT_MARGIN, y + 4, TextColors.WHITE);
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(size), x + width - TEXT_MARGIN - minecraft.font.width(size), y + 4, TextColors.WHITE);
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, detail(), Math.max(1, width - TEXT_MARGIN * 2))).withStyle(ChatFormatting.GRAY), x + TEXT_MARGIN, y + 14, TextColors.WHITE);
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
