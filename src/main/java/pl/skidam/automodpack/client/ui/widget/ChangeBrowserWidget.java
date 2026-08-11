package pl.skidam.automodpack.client.ui.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
import pl.skidam.automodpack_core.change.ChangeBrowserProjection;
import pl.skidam.automodpack_core.change.ChangeSet;

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

/** A native selection list for the shared tree/list change projection. */
public final class ChangeBrowserWidget extends ObjectSelectionList<ChangeBrowserWidget.Entry> {
	private static final int ROW_HEIGHT = 30;
	private final Consumer<String> folderToggle;

	public ChangeBrowserWidget(ChangeBrowserProjection.Projection projection, Set<String> collapsedFolders, Map<String, String> featureNames,
			boolean technicalDetails, Consumer<String> folderToggle, Minecraft client, int width, int height, int top, int bottom) {
		/*? if <1.20.3 {*/
		/*super(client, width, height, top, bottom, ROW_HEIGHT);
		*//*?} else {*/
		super(client, width, Math.max(ROW_HEIGHT, bottom - top), top, ROW_HEIGHT);
		/*?}*/
		this.centerListVertically = false;
		this.folderToggle = Objects.requireNonNull(folderToggle, "folder toggle");
		Set<String> collapsed = Set.copyOf(collapsedFolders == null ? Set.of() : collapsedFolders);
		Map<String, String> names = Map.copyOf(featureNames == null ? Map.of() : featureNames);
		for (ChangeBrowserProjection.Row row : projection.rows()) this.addEntry(new Entry(row, collapsed.contains(row.path()), names, technicalDetails));
	}

	public ChangeBrowserProjection.FileRow selectedFile() {
		Entry selected = this.getSelected();
		return selected == null || !(selected.row instanceof ChangeBrowserProjection.FileRow file) ? null : file;
	}

	private void activate(Entry entry) {
		if (entry.row instanceof ChangeBrowserProjection.FolderRow folder) folderToggle.accept(folder.path());
	}

	protected int getScrollbarPosition() {
		return Math.min(this.width - 6, this.width / 2 + this.getRowWidth() / 2 + 6);
	}

	@Override
	public int getRowWidth() {
		return Math.min(600, Math.max(1, this.width - 24));
	}

	public final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final ChangeBrowserProjection.Row row;
		private final boolean collapsed;
		private final Map<String, String> featureNames;
		private final boolean technicalDetails;

		private Entry(ChangeBrowserProjection.Row row, boolean collapsed, Map<String, String> featureNames, boolean technicalDetails) {
			this.row = Objects.requireNonNull(row, "browser row");
			this.collapsed = collapsed;
			this.featureNames = featureNames;
			this.technicalDetails = technicalDetails;
		}

		@Override
		public @NotNull Component getNarration() {
			return VersionedText.literal(row.path() + ", " + detail());
		}

		/*? if >= 26.1 {*/
		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getContentX(), this.getContentY(), this.getContentWidth());
		}
		/*?} elif >= 1.21.9 {*/
		/*@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), ChangeBrowserWidget.this.getRowWidth());
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
			int indent = Math.min(72, row.depth() * 12);
			String marker = marker();
			String label = row instanceof ChangeBrowserProjection.FolderRow ? leafName(row.path()) : row.path();
			ChatFormatting color = row instanceof ChangeBrowserProjection.FileRow file ? kindColor(file.kind()) : ChatFormatting.WHITE;
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font,
					VersionedText.literal(truncate(minecraft, marker + label, Math.max(1, entryWidth - indent - 12))).withStyle(color), x + indent + 6, y + 4, TextColors.WHITE);
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font,
					VersionedText.literal(truncate(minecraft, detail(), Math.max(1, entryWidth - indent - 22))).withStyle(ChatFormatting.GRAY), x + indent + 16, y + 17, TextColors.WHITE);
		}

		private String marker() {
			if (row instanceof ChangeBrowserProjection.FolderRow) return collapsed ? "+ " : "- ";
			ChangeBrowserProjection.FileRow file = (ChangeBrowserProjection.FileRow) row;
			return switch (file.kind()) {
				case ADDED -> "+ ";
				case REMOVED -> "- ";
				case MODIFIED, METADATA_ONLY -> "~ ";
				case UNSAFE -> "! ";
				default -> "  ";
			};
		}

		private String detail() {
			if (row instanceof ChangeBrowserProjection.FolderRow folder)
				return folderDetail(folder.aggregate());
			ChangeBrowserProjection.FileRow file = (ChangeBrowserProjection.FileRow) row;
			if (technicalDetails) return technicalDetail(file);
			List<String> parts = new ArrayList<>();
			parts.add(kindName(file.kind()));
			parts.add(UiFormat.formatSize(file.size()));
			if (!file.contentKinds().isEmpty()) parts.add(String.join(", ", file.contentKinds().stream().map(ChangeBrowserWidget::contentName).toList()));
			List<String> visibleFeatures = file.features().stream().map(featureNames::get).filter(name -> name != null && !name.isBlank()).distinct().sorted().toList();
			if (!visibleFeatures.isEmpty()) parts.add(String.join(", ", visibleFeatures));
			return String.join(" | ", parts);
		}

		private String technicalDetail(ChangeBrowserProjection.FileRow file) {
			Set<String> locations = new TreeSet<>();
			Set<String> hashes = new TreeSet<>();
			Set<String> features = new TreeSet<>();
			int references = 0;
			for (ChangeSet.Occurrence occurrence : file.occurrences()) {
				locations.add(occurrence.location());
				features.addAll(occurrence.featureIds());
				String hash = occurrence.afterHash() == null ? occurrence.beforeHash() : occurrence.afterHash();
				if (hash != null) hashes.add(hash.substring(0, Math.min(12, hash.length())));
				references += occurrence.references().size();
			}
			List<String> parts = new ArrayList<>();
			parts.add(UiFormat.formatSize(file.size()));
			parts.add(String.join(", ", locations));
			if (!features.isEmpty()) parts.add(String.join(", ", features));
			if (!hashes.isEmpty()) parts.add(String.join(", ", hashes));
			if (references > 0) parts.add(VersionedText.translatable("automodpack.browser.references", references).getString());
			return String.join(" | ", parts);
		}

		private static String folderDetail(ChangeBrowserProjection.Aggregate aggregate) {
			List<String> parts = new ArrayList<>();
			parts.add(VersionedText.translatable("automodpack.browser.folderSummary", aggregate.fileCount(), UiFormat.formatSize(aggregate.byteCount())).getString());
			long added = aggregate.forKind(ChangeSet.Kind.ADDED).fileCount();
			long modified = aggregate.forKind(ChangeSet.Kind.MODIFIED).fileCount() + aggregate.forKind(ChangeSet.Kind.METADATA_ONLY).fileCount();
			long removed = aggregate.forKind(ChangeSet.Kind.REMOVED).fileCount();
			long unsafe = aggregate.forKind(ChangeSet.Kind.UNSAFE).fileCount();
			if (added > 0) parts.add("+" + added);
			if (modified > 0) parts.add("~" + modified);
			if (removed > 0) parts.add("-" + removed);
			if (unsafe > 0) parts.add("!" + unsafe);
			return String.join(" | ", parts);
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

	private static ChatFormatting kindColor(ChangeSet.Kind kind) {
		return switch (kind) {
			case ADDED -> ChatFormatting.GREEN;
			case REMOVED, UNSAFE -> ChatFormatting.RED;
			case MODIFIED, METADATA_ONLY -> ChatFormatting.YELLOW;
			default -> ChatFormatting.WHITE;
		};
	}

	private static String kindName(ChangeSet.Kind kind) {
		return VersionedText.translatable("automodpack.browser.kind." + kind.name().toLowerCase(java.util.Locale.ROOT)).getString();
	}

	private static String contentName(String contentKind) {
		return VersionedText.translatable("automodpack.browser.content." + contentKind).getString();
	}

	private static String leafName(String path) {
		int separator = path.lastIndexOf('/');
		return separator < 0 ? path : path.substring(separator + 1);
	}

	private static String truncate(Minecraft client, String text, int width) {
		if (client.font.width(text) <= width) return text;
		String ellipsis = "...";
		int end = text.length();
		while (end > 0 && client.font.width(text.substring(0, end) + ellipsis) > width) end--;
		return text.substring(0, end).stripTrailing() + ellipsis;
	}
}
