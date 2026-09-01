package pl.skidam.automodpack.client.ui.widget;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
	private static final int BADGE_SPACING = 4;
	private static final int BADGE_MARGIN = 6;
	private static final int BADGE_MODRINTH_COLOR = 0xFF00AF5C;
	private static final int BADGE_CURSEFORGE_COLOR = 0xFFF16436;
	private static final int BADGE_UNVERIFIED_COLOR = 0xFFFF5555;
	private final Consumer<String> folderToggle;
	private final Consumer<ChangeBrowserProjection.FileRow> selectionChanged;

	public ChangeBrowserWidget(ChangeBrowserProjection.Projection projection, Set<String> collapsedFolders, Map<String, String> featureNames,
			Consumer<String> folderToggle, Consumer<ChangeBrowserProjection.FileRow> selectionChanged, Minecraft client, int width, int height, int top, int bottom) {
		/*? if <1.20.3 {*/
		/*super(client, width, height, top, bottom, ROW_HEIGHT);
		*//*?} else {*/
		super(client, width, Math.max(ROW_HEIGHT, bottom - top), top, ROW_HEIGHT);
		/*?}*/
		this.centerListVertically = false;
		this.folderToggle = Objects.requireNonNull(folderToggle, "folder toggle");
		this.selectionChanged = selectionChanged;
		Set<String> collapsed = Set.copyOf(collapsedFolders == null ? Set.of() : collapsedFolders);
		Map<String, String> names = Map.copyOf(featureNames == null ? Map.of() : featureNames);
		for (ChangeBrowserProjection.Row row : projection.rows()) this.addEntry(new Entry(row, collapsed.contains(row.path()), names));
	}

	public ChangeBrowserProjection.FileRow selectedFile() {
		Entry selected = this.getSelected();
		return selected == null || !(selected.row instanceof ChangeBrowserProjection.FileRow file) ? null : file;
	}

	public void selectPath(String path) {
		if (path == null || path.isBlank()) {
			this.setSelected(null);
			return;
		}
		for (Entry entry : this.children()) {
			if (entry.row instanceof ChangeBrowserProjection.FileRow file && file.path().equals(path)) {
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

	private void activate(Entry entry) {
		this.setSelected(entry);
		if (entry.row instanceof ChangeBrowserProjection.FolderRow folder) {
			folderToggle.accept(folder.path());
			return;
		}
		if (selectionChanged != null) selectionChanged.accept(entry.row instanceof ChangeBrowserProjection.FileRow file ? file : null);
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
		private final List<Badge> badges;

		private Entry(ChangeBrowserProjection.Row row, boolean collapsed, Map<String, String> featureNames) {
			this.row = Objects.requireNonNull(row, "browser row");
			this.collapsed = collapsed;
			this.featureNames = featureNames;
			this.badges = badges(row);
		}

		@Override
		public @NotNull Component getNarration() {
			if (row instanceof ChangeBrowserProjection.EffectRow effect) return VersionedText.literal(effectName(effect.effect()) + ", " + detail());
			return VersionedText.literal(row.path() + ", " + detail());
		}

		/*? if >= 26.1 {*/
		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getContentX(), this.getContentY(), this.getContentWidth(), mouseX, mouseY, hovered);
		}
		/*?} elif >= 1.21.9 {*/
		/*@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), ChangeBrowserWidget.this.getRowWidth(), mouseX, mouseY, hovered);
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
			versionedRender(versionedMatrices, x, y, entryWidth, mouseX, mouseY, hovered);
		}
		*//*?}*/

		private void versionedRender(VersionedMatrices matrices, int x, int y, int entryWidth, int mouseX, int mouseY, boolean hovered) {
			int indent = Math.min(72, row.depth() * 12);
			int badgeWidth = 0;
			for (Badge badge : badges) badgeWidth += minecraft.font.width(badge.text()) + BADGE_SPACING;
			badgeWidth = Math.max(0, badgeWidth - BADGE_SPACING);
			String marker = marker();
			String label = row instanceof ChangeBrowserProjection.EffectRow effect ? effectName(effect.effect()) : row instanceof ChangeBrowserProjection.FolderRow || row.depth() > 0 ? leafName(row.path()) : row.path();
			ChatFormatting color = row instanceof ChangeBrowserProjection.FileRow file ? kindColor(file.kind()) : row instanceof ChangeBrowserProjection.EffectRow effect ? kindColor(effectKind(effect.effect())) : ChatFormatting.WHITE;
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font,
					VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, marker + label, Math.max(1, entryWidth - indent - 12 - badgeWidth))).withStyle(color), x + indent + 6, y + 4, TextColors.WHITE);
			int badgeRight = x + entryWidth - BADGE_MARGIN;
			for (int index = badges.size() - 1; index >= 0; index--) {
				Badge badge = badges.get(index);
				badgeRight -= minecraft.font.width(badge.text());
				VersionedScreen.drawTextWithShadow(matrices, minecraft.font, VersionedText.literal(badge.text()), badgeRight, y + 4, badge.color());
				badgeRight -= BADGE_SPACING;
			}
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font,
					VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, detail(), Math.max(1, entryWidth - indent - 22))).withStyle(ChatFormatting.GRAY), x + indent + 16, y + 17, TextColors.WHITE);
			/*? if >=1.21.8 {*/
			if (hovered) tooltip(matrices, mouseX, mouseY);
			/*?}*/
		}

		private record Badge(String text, String key, int color) {}

		/** Right-aligned first-line tags: the storefronts this file is published on, or the unverified mark for plain jars. */
		private static List<Badge> badges(ChangeBrowserProjection.Row row) {
			if (!(row instanceof ChangeBrowserProjection.FileRow file)) return List.of();
			List<Badge> badges = new ArrayList<>();
			boolean modrinth = false;
			boolean curseforge = false;
			for (ChangeSet.Occurrence occurrence : file.occurrences()) {
				for (String reference : occurrence.references()) {
					String platform = platform(reference);
					if (platform.equals("modrinth")) modrinth = true;
					else if (platform.equals("curseforge")) curseforge = true;
				}
			}
			if (modrinth) badges.add(new Badge("MR", "automodpack.browser.modrinth", BADGE_MODRINTH_COLOR));
			if (curseforge) badges.add(new Badge("CF", "automodpack.browser.curseforge", BADGE_CURSEFORGE_COLOR));
			if (badges.isEmpty() && file.path().toLowerCase(Locale.ROOT).endsWith(".jar")) badges.add(new Badge(VersionedText.translatable("automodpack.browser.unverifiedShort").getString(), null, BADGE_UNVERIFIED_COLOR));
			return badges;
		}

		/*? if >=1.21.8 {*/
		private void tooltip(VersionedMatrices matrices, int mouseX, int mouseY) {
			if (!(row instanceof ChangeBrowserProjection.FileRow file)) return;
			List<Component> lines = new ArrayList<>();
			lines.add(VersionedText.literal(row.path()));
			String after = null;
			String before = null;
			for (ChangeSet.Occurrence occurrence : file.occurrences()) {
				if (after == null) after = occurrence.afterHash();
				if (before == null) before = occurrence.beforeHash();
			}
			String hash = shortHash(after != null ? after : before);
			if (hash != null) lines.add(VersionedText.literal("sha1: " + hash));
			if (before != null && after != null && !before.equals(after)) lines.add(VersionedText.literal(shortHash(before) + " -> " + shortHash(after)));
			List<String> platforms = badges.stream().filter(badge -> badge.key() != null).map(badge -> VersionedText.translatable(badge.key()).getString()).toList();
			if (!platforms.isEmpty()) lines.add(VersionedText.literal(String.join(", ", platforms)));
			matrices.getContext().setComponentTooltipForNextFrame(minecraft.font, lines, mouseX, mouseY);
		}
		/*?}*/

		private String marker() {
			if (row instanceof ChangeBrowserProjection.FolderRow) return collapsed ? "+ " : "- ";
			if (row instanceof ChangeBrowserProjection.EffectRow effect) return marker(effectKind(effect.effect()));
			ChangeBrowserProjection.FileRow file = (ChangeBrowserProjection.FileRow) row;
			return marker(file.kind());
		}

		private String marker(ChangeSet.Kind kind) {
			return switch (kind) {
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
			if (row instanceof ChangeBrowserProjection.EffectRow effect) return kindName(effectKind(effect.effect()));
			ChangeBrowserProjection.FileRow file = (ChangeBrowserProjection.FileRow) row;
			List<String> parts = new ArrayList<>();
			parts.add(kindName(file.kind()));
			parts.add(UiFormat.formatSize(file.size()));
			if (!file.contentKinds().isEmpty()) parts.add(String.join(", ", file.contentKinds().stream().map(ChangeBrowserWidget::contentName).toList()));
			List<String> visibleFeatures = file.features().stream().map(featureNames::get).filter(name -> name != null && !name.isBlank()).distinct().sorted().toList();
			if (!visibleFeatures.isEmpty()) parts.add(String.join(", ", visibleFeatures));
			return String.join(" | ", parts);
		}

		private static String folderDetail(ChangeBrowserProjection.Aggregate aggregate) {
			List<String> parts = new ArrayList<>();
			parts.add(UiFormat.plural(aggregate.fileCount(), "automodpack.browser.folderSummary", UiFormat.formatSize(aggregate.byteCount())).getString());
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

		private String effectName(ChangeSet.Effect effect) {
			if (effect.category().startsWith("group.")) {
				String name = featureNames.get(effect.value());
				return name == null || name.isBlank() ? VersionedText.translatable("automodpack.browser.unknownFeature").getString() : name;
			}
			return VersionedText.translatable("automodpack.ui.general").getString();
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

	private static ChangeSet.Kind effectKind(ChangeSet.Effect effect) {
		if (effect.category().endsWith(".added")) return ChangeSet.Kind.ADDED;
		if (effect.category().endsWith(".removed")) return ChangeSet.Kind.REMOVED;
		return ChangeSet.Kind.METADATA_ONLY;
	}

	/** Storefront a reference URL belongs to, or "" for every other host; badges only cover the vetted platforms. */
	private static String platform(String url) {
		try {
			String host = new URI(url).getHost();
			if (host == null || host.isBlank()) return "";
			String lower = host.toLowerCase(Locale.ROOT);
			if (lower.equals("modrinth.com") || lower.endsWith(".modrinth.com")) return "modrinth";
			if (lower.equals("curseforge.com") || lower.endsWith(".curseforge.com") || lower.equals("curseforge.net") || lower.endsWith(".curseforge.net")) return "curseforge";
			return "";
		} catch (URISyntaxException | IllegalArgumentException ignored) {
			return "";
		}
	}

	private static String shortHash(String hash) {
		return hash == null ? null : hash.substring(0, Math.min(12, hash.length()));
	}

	private static String kindName(ChangeSet.Kind kind) {
		return VersionedText.translatable("automodpack.browser.kind." + kind.name().toLowerCase(Locale.ROOT)).getString();
	}

	private static String contentName(String contentKind) {
		return VersionedText.translatable("automodpack.browser.content." + contentKind).getString();
	}

	private static String leafName(String path) {
		int separator = path.lastIndexOf('/');
		return separator < 0 ? path : path.substring(separator + 1);
	}
}
