package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/*? if >= 1.21.9 {*/
import net.minecraft.client.input.MouseButtonEvent;
/*?}*/

/** Nested list of unverified jar paths with their sizes for the unverified confirm screen. */
public final class UnverifiedJarList extends ChromelessList<UnverifiedJarList.Entry> {
	public static final int ROW_HEIGHT = 12;

	/** One unverified file: its path as shipped by the pack and its size, 0 when unknown. */
	public record UnverifiedFile(String path, long size) {
		public UnverifiedFile {
			path = Objects.requireNonNull(path, "unverified file path");
			if (size < 0) throw new IllegalArgumentException("Unverified file size is negative");
		}
	}

	public UnverifiedJarList(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<UnverifiedFile> files) {
		super(client, width, height, 0, top, bottom, contentWidth, ROW_HEIGHT);
		for (UnverifiedFile file : Objects.requireNonNull(files, "files")) this.addEntry(new Entry(file));
		if (!this.children().isEmpty()) this.setSelected(this.children().get(0));
	}

	public final class Entry extends Row<Entry> {
		private final UnverifiedFile file;

		private Entry(UnverifiedFile file) {
			this.file = file;
		}

		@Override
		public @NotNull Component getNarration() {
			return VersionedText.literal(file.path());
		}

		@Override
		protected void versionedRender(VersionedMatrices matrices, int x, int y, int width, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			boolean selected = UnverifiedJarList.this.getSelected() == this;
			String sizeText = file.size() > 0 ? UiFormat.formatSize(file.size()) : "";
			int sizeWidth = sizeText.isEmpty() ? 0 : minecraft.font.width(sizeText);
			String label = VersionedScreen.truncateToWidth(minecraft.font, file.path(), Math.max(1, width - sizeWidth - 6));
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(label), x + 2, y + 1, selected ? TextColors.LIGHT_YELLOW : TextColors.LIGHT_GRAY);
			if (!sizeText.isEmpty()) VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(sizeText), x + width - sizeWidth, y + 1, TextColors.GRAY);
		}

		/*? if >= 1.21.9 {*/
		@Override
		public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
			return true;
		}
		/*?} else {*/
		/*@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			return true;
		}
		*//*?}*/
	}
}
