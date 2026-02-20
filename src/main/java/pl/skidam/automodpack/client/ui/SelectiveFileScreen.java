package pl.skidam.automodpack.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.ListEntry;
import pl.skidam.automodpack.client.ui.widget.ListEntryWidget;

import java.util.Set;

/*? if >= 1.21.9 {*/
import net.minecraft.client.input.MouseButtonEvent;
/*?}*/


public class SelectiveFileScreen extends VersionedScreen {

    private final Screen parent;
    private final String groupId;
    private final Jsons.ModpackGroupFields group;

    private final Set<String> selectedFiles;

    private FileListWidget listWidget;

    public SelectiveFileScreen(Screen parent, String groupId, Jsons.ModpackGroupFields group, Set<String> selectedFiles) {
        super(VersionedText.literal("Configure: " + group.displayName));
        this.parent = parent;
        this.groupId = groupId;
        this.group = group;
        this.selectedFiles = selectedFiles;
    }

    @Override
    protected void init() {
        super.init();

        this.listWidget = new FileListWidget(this.minecraft, this.width, this.height, 40, this.height - 40, 20);
        if (group.files != null) {
            for (Jsons.ModpackContentItem item : group.files) {
                this.listWidget.addEntry(new FileEntry(item));
            }
        }
        this.addRenderableWidget(this.listWidget);

        this.addRenderableWidget(buttonWidget(this.width / 2 - 155, this.height - 30, 100, 20, VersionedText.literal("Deselect All"), button -> {
            selectedFiles.clear();
        }));

        this.addRenderableWidget(buttonWidget(this.width / 2 - 50, this.height - 30, 100, 20, VersionedText.literal("Select All"), button -> {
            if (group.files != null) for (var item : group.files) selectedFiles.add(item.file);
        }));

        this.addRenderableWidget(buttonWidget(this.width / 2 + 55, this.height - 30, 100, 20, VersionedText.literal("Done"), button -> {
            this.minecraft.setScreen(parent);
        }));
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        if (this.listWidget != null) {
            this.listWidget.render(matrices.getContext(), mouseX, mouseY, delta);
        }

        drawCenteredText(matrices, this.font, this.title.copy(), this.width / 2, 20, 0xFFFFFF);
    }

    class FileListWidget extends ListEntryWidget {
        public FileListWidget(Minecraft client, int width, int height, int top, int bottom, int itemHeight) {
            super(null, client, width, height, top, bottom, itemHeight);
            this.clearEntries(); // Strip out the default "No changelogs found" placeholder
        }

        public void addEntry(FileEntry entry) {
            super.addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return this.width - 40; // Maximize row width to stretch across the screen
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width - 15; // Push the scrollbar to the far right edge
        }
    }

    class FileEntry extends ListEntry {
        private final Jsons.ModpackContentItem item;

        public FileEntry(Jsons.ModpackContentItem item) {
            super(VersionedText.literal(""), false, SelectiveFileScreen.this.minecraft);
            this.item = item;
        }

        @Override
        public void versionedRender(VersionedMatrices matrices, int x, int y, int entryWidth, int entryHeight) {
            boolean isSelected = selectedFiles.contains(item.file);
            String boxText = isSelected ? "[X]" : "[ ]";

            drawText(matrices, minecraft.font, boxText, x + 5, y + 4, 0xFFFFFF);
            drawText(matrices, minecraft.font, item.file, x + 25, y + 4, 0xAAAAAA);
        }

        @Override
        public Component getNarration() {
            return VersionedText.literal(item.file);
        }

        /*? if >= 1.21.9 {*/
        @Override
        public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
            return mouseClickedInternal();
        }
        /*?} else {*/
        /*@Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return mouseClickedInternal();
        }*//*?}*/

        private boolean mouseClickedInternal() {
            if (selectedFiles.contains(item.file)) {
                selectedFiles.remove(item.file);
            } else {
                selectedFiles.add(item.file);
            }
            return true;
        }
    }
}