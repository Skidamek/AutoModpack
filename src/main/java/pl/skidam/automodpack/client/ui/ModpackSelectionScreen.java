package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ClientSelectionManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.ListEntry;
import pl.skidam.automodpack.client.ui.widget.ListEntryWidget;

import java.util.*;

/*? if >= 1.21.9 {*/
import net.minecraft.client.input.MouseButtonEvent;
/*?}*/

public class ModpackSelectionScreen extends VersionedScreen {

    private final Screen parent;
    private final ModpackUpdater updater;
    private final Jsons.ModpackContent content;

    private GroupListWidget listWidget;
    private boolean isValid = true;
    private String validationError = "";

    public final Set<String> selectedGroups = new HashSet<>();
    public final Map<String, Set<String>> selectedSelectiveFiles = new HashMap<>();

    public ModpackSelectionScreen(Screen parent, ModpackUpdater updater, Jsons.ModpackContent content) {
        super(VersionedText.literal("Select Modpack Groups"));
        this.parent = parent;
        this.updater = updater;
        this.content = content;

        validatePack();
        if (isValid) {
            initSelections();
        }
    }

    @Override
    protected void init() {
        super.init();
        if (isValid) {
            this.listWidget = new GroupListWidget(this.minecraft, this.width, this.height, 40, this.height - 40, 30);

            // Sort groups: 1. Req+Rec, 2. Req, 3. Rec, 4. Rest (Stable sort preserves definition order for ties)
            List<Map.Entry<String, Jsons.ModpackGroupFields>> sortedEntries = new ArrayList<>(content.groups.entrySet());
            sortedEntries.sort((e1, e2) -> {
                Jsons.ModpackGroupFields g1 = e1.getValue();
                Jsons.ModpackGroupFields g2 = e2.getValue();

                int score1 = (g1.required ? 2 : 0) + (g1.recommended ? 1 : 0);
                int score2 = (g2.required ? 2 : 0) + (g2.recommended ? 1 : 0);

                if (score1 != score2) {
                    return Integer.compare(score2, score1); // Descending
                }

                // Fallback to alphabetical sorting to ensure deterministic order (since HashMap scrambles it)
                String name1 = g1.displayName != null ? g1.displayName : e1.getKey();
                String name2 = g2.displayName != null ? g2.displayName : e2.getKey();
                return name1.compareToIgnoreCase(name2);
            });

            // Populate list
            for (Map.Entry<String, Jsons.ModpackGroupFields> entry : sortedEntries) {
                this.listWidget.addEntry(new GroupEntry(entry.getKey(), entry.getValue()));
            }
            this.addRenderableWidget(this.listWidget);

            // Swapped Layout: <Cancel> <Install>
            this.addRenderableWidget(buttonWidget(this.width / 2 - 105, this.height - 30, 100, 20, VersionedText.translatable("automodpack.danger.cancel"), button -> this.minecraft.setScreen(parent)));
            this.addRenderableWidget(buttonWidget(this.width / 2 + 5, this.height - 30, 100, 20, VersionedText.literal("Install"), button -> install()));
        } else {
            // If invalid, just show Cancel in the middle
            this.addRenderableWidget(buttonWidget(this.width / 2 - 50, this.height - 30, 100, 20, VersionedText.translatable("automodpack.danger.cancel"), button -> this.minecraft.setScreen(parent)));
        }
    }

    private void validatePack() {
        for (Map.Entry<String, Jsons.ModpackGroupFields> entry : content.groups.entrySet()) {
            String id = entry.getKey();
            Jsons.ModpackGroupFields group = entry.getValue();
            if (group.required) {
                if (group.requires != null) {
                    for (String req : group.requires) {
                        if (!content.groups.containsKey(req)) {
                            isValid = false;
                            validationError = "Broken Pack: Required group '" + group.displayName + "' requires missing group '" + req + "'";
                            return;
                        }
                    }
                }
                if (group.breaksWith != null) {
                    for (String conflict : group.breaksWith) {
                        Jsons.ModpackGroupFields conflictGroup = content.groups.get(conflict);
                        if (conflictGroup != null && conflictGroup.required) {
                            isValid = false;
                            validationError = "Broken Pack: Conflict between required groups '" + group.displayName + "' and '" + conflictGroup.displayName + "'";
                            return;
                        }
                    }
                }
            }
        }
    }

    private void initSelections() {
        ClientSelectionManager mgr = ClientSelectionManager.getMgr();
        List<Jsons.ClientSelectionManagerFields.Group> savedGroups = mgr.getSelectedGroups(content.modpackName);
        boolean hasSavedSelection = savedGroups != null && !savedGroups.isEmpty();

        List<String> savedGroupIds = new ArrayList<>();
        if (hasSavedSelection) {
            for (Jsons.ClientSelectionManagerFields.Group g : savedGroups) {
                savedGroupIds.add(g.groupId);
                if (g.selectedFiles != null) {
                    selectedSelectiveFiles.put(g.groupId, new HashSet<>(g.selectedFiles));
                }
            }
        }

        for (Map.Entry<String, Jsons.ModpackGroupFields> entry : content.groups.entrySet()) {
            String id = entry.getKey();
            Jsons.ModpackGroupFields group = entry.getValue();

            if (group.selective) {
                // Pre-fill selective sets with all files if not saved
                if (!hasSavedSelection || !selectedSelectiveFiles.containsKey(id)) {
                    Set<String> files = new HashSet<>();
                    if (group.files != null) {
                        for (Jsons.ModpackContentItem item : group.files) files.add(item.file);
                    }
                    selectedSelectiveFiles.put(id, files);
                }
            }

            if (isOsCompatible(group.compatibleOS)) {
                if (hasSavedSelection) {
                    if (savedGroupIds.contains(id) || group.required) toggleGroup(id, true);
                } else {
                    if (group.required || group.recommended) toggleGroup(id, true);
                }
            }
        }
    }

    public void toggleGroup(String id, boolean state) {
        Jsons.ModpackGroupFields group = content.groups.get(id);
        if (group == null || !isOsCompatible(group.compatibleOS)) return;

        if (state || group.required) { // Always force state to true if it's required
            if (!selectedGroups.contains(id)) {
                selectedGroups.add(id);

                // Disable conflicts explicitly broken by THIS group
                if (group.breaksWith != null) {
                    for (String conflict : group.breaksWith) {
                        if (selectedGroups.contains(conflict)) {
                            toggleGroup(conflict, false);
                        }
                    }
                }

                // Disable groups that explicitly break THIS group (Bidirectional conflict)
                for (String otherId : new ArrayList<>(selectedGroups)) {
                    Jsons.ModpackGroupFields other = content.groups.get(otherId);
                    if (other != null && other.breaksWith != null && other.breaksWith.contains(id)) {
                        toggleGroup(otherId, false);
                    }
                }

                // Enable requirements recursively
                if (group.requires != null) {
                    for (String req : group.requires) {
                        if (!selectedGroups.contains(req) && content.groups.containsKey(req)) {
                            toggleGroup(req, true);
                        }
                    }
                }
            }
        } else {
            if (selectedGroups.contains(id)) {
                selectedGroups.remove(id);

                // Disable dependents recursively (groups that require this group)
                for (String otherId : new ArrayList<>(selectedGroups)) {
                    Jsons.ModpackGroupFields other = content.groups.get(otherId);
                    if (other != null && other.requires != null && other.requires.contains(id)) {
                        toggleGroup(otherId, false);
                    }
                }
            }
        }
    }

    private void install() {
        ClientSelectionManager mgr = ClientSelectionManager.getMgr();
        if (!mgr.packExists(content.modpackName)) {
            mgr.addPack(content.modpackName, new Jsons.ClientSelectionManagerFields.Modpack(new Jsons.ModpackAddresses()));
        }

        // Assemble Groups map
        List<Jsons.ClientSelectionManagerFields.Group> finalGroupsToSave = new ArrayList<>();
        for (String id : selectedGroups) {
            List<String> filesForGroup = new ArrayList<>();
            Jsons.ModpackGroupFields group = content.groups.get(id);
            // add all groups even if the group is not selective because it might become one someday, so save all files which we download currently
            if (group != null) {
                if (group.selective && selectedSelectiveFiles.containsKey(id)) {
                    filesForGroup.addAll(selectedSelectiveFiles.get(id));
                } else if (!group.selective) {
                    filesForGroup.addAll(group.files.stream().map(modpackContentItem -> modpackContentItem.file).toList());
                }
            }
            finalGroupsToSave.add(new Jsons.ClientSelectionManagerFields.Group(id, filesForGroup));
        }

        mgr.setSelectedGroups(content.modpackName, finalGroupsToSave);
        mgr.setSelectedPack(content.modpackName);

        Set<Jsons.ModpackContentItem> finalFiles = new HashSet<>();
        for (String id : selectedGroups) {
            Jsons.ModpackGroupFields group = content.groups.get(id);
            if (group == null || group.files == null) continue;

            if (group.selective) {
                Set<String> selectedFiles = selectedSelectiveFiles.get(id);
                if (selectedFiles != null) {
                    for (Jsons.ModpackContentItem file : group.files) {
                        if (selectedFiles.contains(file.file)) finalFiles.add(file);
                    }
                }
            } else {
                finalFiles.addAll(group.files);
            }
        }

        // Run update asynchronously so the UI successfully transitions to the download screen without blocking
        Util.backgroundExecutor().execute(() -> updater.startUpdate(finalFiles));
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        if (!isValid) {
            drawCenteredText(matrices, this.font, VersionedText.literal(validationError).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), this.width / 2, this.height / 2, 0xFF5555);
            drawCenteredText(matrices, this.font, VersionedText.literal("Installation Blocked. Please contact the server admin."), this.width / 2, this.height / 2 + 15, 0xAAAAAA);
        } else {
            if (this.listWidget != null) {
                this.listWidget.render(matrices.getContext(), mouseX, mouseY, delta);
            }
            drawCenteredText(matrices, this.font, this.title.copy(), this.width / 2, 20, 0xFFFFFF);
        }
    }

    public static boolean isOsCompatible(List<String> compatibleOS) {
        if (compatibleOS == null || compatibleOS.isEmpty()) return true;

        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String javaVendor = System.getProperty("java.vendor").toLowerCase(Locale.ROOT);
        String javaVmName = System.getProperty("java.vm.name").toLowerCase(Locale.ROOT);

        String currentOs = "UNKNOWN";
        if (osName.contains("win")) currentOs = "WINDOWS";
        else if (osName.contains("mac")) currentOs = "MACOS";
        else if (javaVendor.contains("android") || javaVmName.contains("dalvik") || javaVmName.contains("lemur")) currentOs = "ANDROID";
        else if (osName.contains("linux") || osName.contains("unix")) currentOs = "LINUX";

        boolean hasIncludes = false;
        boolean explicitlyIncluded = false;
        boolean explicitlyExcluded = false;

        for (String osReq : compatibleOS) {
            String cleanReq = osReq.trim().toUpperCase(Locale.ROOT);
            boolean isNegation = cleanReq.startsWith("!");
            String target = isNegation ? cleanReq.substring(1) : cleanReq;

            if (!isNegation) {
                hasIncludes = true;
                if (currentOs.equals(target)) explicitlyIncluded = true;
            } else {
                if (currentOs.equals(target)) explicitlyExcluded = true;
            }
        }

        if (explicitlyExcluded) return false;
        if (hasIncludes) return explicitlyIncluded;
        return true; // Return true if only negations exist and none matched
    }

    class GroupListWidget extends ListEntryWidget {
        public GroupListWidget(Minecraft client, int width, int height, int top, int bottom, int itemHeight) {
            super(null, client, width, height, top, bottom, itemHeight);
            this.clearEntries();
        }

        public void addEntry(GroupEntry entry) {
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

    class GroupEntry extends ListEntry {
        private final String groupId;
        private final Jsons.ModpackGroupFields group;
        private int currentX, currentY, currentWidth;

        public GroupEntry(String groupId, Jsons.ModpackGroupFields group) {
            super(VersionedText.literal(""), false, ModpackSelectionScreen.this.minecraft);
            this.groupId = groupId;
            this.group = group;
        }

        @Override
        public void versionedRender(VersionedMatrices matrices, int x, int y, int entryWidth, int entryHeight) {
            this.currentX = x;
            this.currentY = y;
            this.currentWidth = entryWidth;

            boolean isSelected = selectedGroups.contains(groupId);
            boolean isCompat = isOsCompatible(group.compatibleOS);

            String boxText = isSelected ? "[X]" : "[ ]";
            if (!isCompat) boxText = "[-]";

            int color = isCompat ? (group.required ? 0xAAAAAA : 0xFFFFFF) : 0xFF5555;

            drawText(matrices, minecraft.font, boxText, x + 5, y + 4, color);
            drawText(matrices, minecraft.font, group.displayName, x + 25, y + 4, color);

            if (group.selective && isCompat) {
                drawText(matrices, minecraft.font, VersionedText.literal("[Configure]"), x + entryWidth - 70, y + 4, 0x55FF55);
            }

            if (!isCompat) {
                drawText(matrices, minecraft.font, VersionedText.literal("Incompatible OS"), x + 25, y + 16, 0xFF5555);
            } else if (group.description != null && !group.description.isEmpty()) {
                drawText(matrices, minecraft.font, group.description, x + 25, y + 16, 0xAAAAAA);
            }
        }

        @Override
        public Component getNarration() {
            return VersionedText.literal(group.displayName + ", " + group.description);
        }

        /*? if >= 1.21.9 {*/
        @Override
        public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
            return mouseClickedInternal(mouseButtonEvent.x(), mouseButtonEvent.y(), mouseButtonEvent.button());
        }
        /*?} else {*/
        /*@Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return mouseClickedInternal(mouseX, mouseY, button);
        }*//*?}*/

        private boolean mouseClickedInternal(double mouseX, double mouseY, int button) {
            if (!isOsCompatible(group.compatibleOS)) return false;

            if (group.selective && mouseX >= currentX + currentWidth - 70 && mouseX <= currentX + currentWidth && mouseY >= currentY && mouseY <= currentY + 20) {
                minecraft.setScreen(new SelectiveFileScreen(ModpackSelectionScreen.this, groupId, group, selectedSelectiveFiles.get(groupId)));
                return true;
            }

            if (!group.required) {
                toggleGroup(groupId, !selectedGroups.contains(groupId));
                return true;
            }
            return false;
        }
    }
}