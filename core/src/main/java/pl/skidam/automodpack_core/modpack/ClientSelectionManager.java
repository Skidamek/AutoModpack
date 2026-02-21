package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.clientSelectionFile;

import java.nio.file.Path;
import java.util.*;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import static pl.skidam.automodpack_core.config.Jsons.ClientSelectionManagerFields;

/**
 * Manages client-side modpack group selections and addresses.
 * Handles saving/loading user preferences locally without requiring active server configuration.
 */
public class ClientSelectionManager {

    private final static ClientSelectionManager MANAGER = new ClientSelectionManager(clientSelectionFile);

    private final Path selectionFile;
    private final ClientSelectionManagerFields selections;

    public static ClientSelectionManager getMgr() {
        return MANAGER;
    }

    private ClientSelectionManager(Path selectionFile) {
        this.selectionFile = selectionFile;
        this.selections = ConfigTools.loadClientSelectionManager(selectionFile);

        // Ensure the modpacks map is initialized if it loads as null from JSON
        if (this.selections.modpacks == null) {
            this.selections.modpacks = new HashMap<>();
        }
    }

    /**
     * Gets the ID of the currently selected modpack.
     * * @return The selected modpack ID, or null if none is selected.
     */
    public String getSelectedPackId() {
        return selections.selectedPack;
    }

    public Jsons.ModpackAddresses getSelectedAddresses() {
        return getModpackAddresses(getSelectedPackId());
    }

    public List<ClientSelectionManagerFields.Group> getSelectedGroups() {
        return getSelectedGroups(getSelectedPackId());
    }

    // TODO make packids somehow unique and independet from modpackNames or server addresses
    public boolean packExists(String packId) {
        return selections.modpacks.containsKey(packId);
    }

    public void removePack(String packId) {
        selections.modpacks.remove(packId);
        save();
    }

    public void addPack(String packId, ClientSelectionManagerFields.Modpack pack) {
        if (selections.modpacks.containsKey(packId)) {
            LOGGER.debug("Overwritting pack {}", packId);
        }
        selections.modpacks.put(packId, pack);
        save();
    }

    /**
     * Sets the currently active modpack ID.
     * * @param packId The ID of the modpack to set as active.
     */
    public void setSelectedPack(String packId) {
        this.selections.selectedPack = packId;
        save();
    }

    /**
     * Internal helper to retrieve or initialize a modpack selection entry.
     */
    private ClientSelectionManagerFields.Modpack getSelection(String packId) {
        return selections.modpacks.get(packId);
    }

    /**
     * Gets the selected groups for a specific modpack.
     *
     * @param packId The modpack ID to query.
     * @return A list of selected group IDs.
     */
    public List<ClientSelectionManagerFields.Group> getSelectedGroups(String packId) {
        if (!selections.modpacks.containsKey(packId)) {
            return new ArrayList<>();
        }

        List<ClientSelectionManagerFields.Group> groups = selections.modpacks.get(packId).selectedGroups;
        return groups == null ? new ArrayList<>() : new ArrayList<>(groups);
    }

    /**
     * Gets the selected groups for the currently active modpack.
     *
     * @return A list of selected group IDs for the active pack.
     */
    public List<ClientSelectionManagerFields.Group> getCurrentSelectedGroups() {
        if (selections.selectedPack == null) {
            return new ArrayList<>();
        }
        return getSelectedGroups(selections.selectedPack);
    }

    /**
     * Dynamically returns the set of files chosen by the user for the specific modpack content.
     * Falls back to default required/recommended combinations if no local selections exist.
     * * @param content The server-provided Modpack Content manifest
     * @return The precise set of selected files to synchronize
     */
    public Set<Jsons.ModpackContentItem> getSelectedFiles(Jsons.ModpackContent content) {
        if (content == null || content.groups == null) {
            return new HashSet<>();
        }

        List<ClientSelectionManagerFields.Group> userSelectedGroups = getSelectedGroups(content.modpackName);
        boolean hasSavedSelection = userSelectedGroups != null && !userSelectedGroups.isEmpty();

        Set<String> savedGroupFilesMap = new HashSet<>();
        if (hasSavedSelection) {
            for (ClientSelectionManagerFields.Group g : userSelectedGroups) {
                savedGroupFilesMap.add(g.groupId);
            }
        }

        Set<Jsons.ModpackContentItem> finalFiles = new HashSet<>();

        for (Map.Entry<String, Jsons.ModpackGroupFields> entry : content.groups.entrySet()) {
            String id = entry.getKey();
            Jsons.ModpackGroupFields group = entry.getValue();

            boolean shouldInclude;
            if (hasSavedSelection) {
                shouldInclude = savedGroupFilesMap.contains(id) || group.required;
            } else {
                shouldInclude = group.required || group.recommended;
            }

            if (shouldInclude && group.files != null) {
                finalFiles.addAll(group.files);
            }
        }

        return finalFiles;
    }

    /**
     * Sets the selected groups for a specific modpack.
     *
     * @param packId The modpack ID.
     * @param selectedGroups The list of group IDs to save.
     */
    public void setSelectedGroups(String packId, List<ClientSelectionManagerFields.Group> selectedGroups) {
        if (packId == null) {
            LOGGER.warn("Attempted to set selected groups for a null pack ID.");
            return;
        }

        if (!packExists(packId)) {
            LOGGER.warn("Attempted to set selected groups for a nonexistant pack");
            return;
        }

        ClientSelectionManagerFields.Modpack selection = getSelection(packId);
        selection.selectedGroups = new ArrayList<>(selectedGroups);
        save();
    }

    /**
     * Gets the addresses associated with a specific modpack.
     * * @param packId The modpack ID to query.
     * @return The modpack addresses, or null if not set.
     */
    public Jsons.ModpackAddresses getModpackAddresses(String packId) {
        if (!selections.modpacks.containsKey(packId)) {
            return null;
        }
        return selections.modpacks.get(packId).modpackAddresses;
    }

    /**
     * Sets the addresses for a specific modpack.
     * * @param packId The modpack ID.
     * @param addresses The addresses to save.
     */
    public void setModpackAddresses(String packId, Jsons.ModpackAddresses addresses) {
        if (packId == null) {
            LOGGER.warn("Attempted to set modpack addresses for a null pack ID.");
            return;
        }

        if (!packExists(packId)) {
            LOGGER.warn("Attempted to set modpack addresses for a nonexistant pack");
            return;
        }

        ClientSelectionManagerFields.Modpack selection = getSelection(packId);
        selection.modpackAddresses = addresses;
        save();
    }

    /**
     * Clears all saved selections and addresses for a specific modpack.
     * * @param packId The modpack ID to clear.
     */
    public void clearSelection(String packId) {
        if (packId != null) {
            selections.modpacks.remove(packId);

            // If we are clearing the currently active pack, reset the active pointer
            if (packId.equals(selections.selectedPack)) {
                selections.selectedPack = null;
            }
            save();
        }
    }

    /**
     * Save the current selections to the filesystem.
     */
    public void save() {
        ConfigTools.saveClientSelectionManager(selectionFile, selections);
    }
}