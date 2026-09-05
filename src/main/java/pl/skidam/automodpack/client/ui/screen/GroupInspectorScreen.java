package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** Player-facing feature details. File projection is delegated to the shared tree/list browser. */
public final class GroupInspectorScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = ActionAreaLayout.FOOTER_RAIL;

	private final Screen parent;
	private final String groupId;
	private final GroupManifest manifest;
	private final GroupManifest.Group group;

	public GroupInspectorScreen(Screen parent, GroupManifest manifest, String groupId) {
		super(VersionedText.translatable("automodpack.groupInspector.title"));
		this.parent = parent;
		this.groupId = groupId;
		this.manifest = manifest;
		this.group = manifest.groups().get(groupId);
		if (this.group == null) throw new IllegalArgumentException("Unknown group: " + groupId);
	}

	@Override
	protected void init() {
		super.init();
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> ScreenImpl.setScreen(parent)),
				primaryAction(VersionedText.translatable("automodpack.groupInspector.browseFiles"), button -> browseFiles()));
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		int wrapWidth = Math.max(1, panelWidth(PANEL_WIDTH) - 8);
		String description = group.description().isBlank() ? VersionedText.translatable("automodpack.groupInspector.noDescription").getString() : group.description();
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapParagraph(this.font, description, wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.selection.category", categoryLabel()).getString(), wrapWidth, ChatFormatting.GRAY));
		lines.addAll(wrapParagraph(this.font, status(), wrapWidth, ChatFormatting.GRAY));
		if (!group.requires().isEmpty()) lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.selection.requires", names(group.requires())).getString(), wrapWidth, ChatFormatting.GRAY));
		if (!group.breaksWith().isEmpty()) lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.selection.conflicts", names(group.breaksWith())).getString(), wrapWidth, ChatFormatting.GRAY));
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.groupInspector.platforms", platforms()).getString(), wrapWidth, ChatFormatting.GRAY));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.selection.files", group.files().size(), UiFormat.formatSize(groupBytes())).getString(), wrapWidth, ChatFormatting.YELLOW));
		DialogColumn column = layoutDialogColumn(32, actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer), lines.size() * LINE_HEIGHT, 0);
		this.addCenteredScrollBody(PANEL_WIDTH, column.bodyTop(), column.bodyBottom(), lines);
	}

	private void browseFiles() {
		String name = displayName();
		ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.literal(name),
				VersionedText.translatable("automodpack.groupInspector.filesDescription"), featureChanges(), Map.of(groupId, name)));
	}

	private ChangeSet featureChanges() {
		List<ChangeSet.Change> changes = new ArrayList<>();
		for (var entry : group.files().entrySet()) {
			GroupManifest.GroupFile file = entry.getValue();
			ChangeSet.Occurrence occurrence = new ChangeSet.Occurrence("catalogue", entry.getKey(), file.size(), null, file.sha1(), file.type(), List.of(groupId), List.of());
			changes.add(new ChangeSet.Change(entry.getKey(), ChangeSet.Kind.PRESERVED, List.of(occurrence)));
		}
		return ChangeSet.of(changes);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, displayName(), panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
	}

	private String displayName() {
		return group.displayName().isBlank() ? VersionedText.translatable("automodpack.browser.unknownFeature").getString() : group.displayName();
	}

	private String status() {
		String statusKey = group.required() ? "automodpack.groupInspector.required" : group.defaultSelected() ? "automodpack.groupInspector.defaultSelected" : "automodpack.groupInspector.optional";
		return VersionedText.translatable("automodpack.groupInspector.status", VersionedText.translatable(statusKey).getString(), group.files().size()).getString();
	}

	private long groupBytes() {
		long total = 0;
		for (GroupManifest.GroupFile file : group.files().values()) total = Math.addExact(total, file.size());
		return total;
	}

	private String categoryLabel() {
		if (group.category().isEmpty()) return VersionedText.translatable("automodpack.ui.general").getString();
		String[] words = group.category().replace('_', ' ').replace('-', ' ').split(" +");
		StringBuilder result = new StringBuilder();
		for (String word : words) {
			if (result.length() > 0) result.append(' ');
			if (!word.isEmpty()) result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return result.toString();
	}

	private String platforms() {
		return group.compatiblePlatforms().isEmpty()
				? VersionedText.translatable("automodpack.groupInspector.allPlatforms").getString()
				: group.compatiblePlatforms().stream().map(ClientPlatform::id).sorted().reduce((first, second) -> first + ", " + second).orElse("");
	}

	private String names(Iterable<String> values) {
		StringBuilder result = new StringBuilder();
		for (String value : values) {
			if (result.length() > 0) result.append(", ");
			GroupManifest.Group related = manifest.groups().get(value);
			result.append(related == null || related.displayName().isBlank() ? VersionedText.translatable("automodpack.browser.unknownFeature").getString() : related.displayName());
		}
		return result.length() == 0 ? VersionedText.translatable("automodpack.ui.none").getString() : result.toString();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
