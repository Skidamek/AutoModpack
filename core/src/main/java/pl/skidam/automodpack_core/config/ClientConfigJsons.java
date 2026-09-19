package pl.skidam.automodpack_core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.loader.PinnedMods;

public class ClientConfigJsons {

	public static class ClientConfigFieldsV3 {
		@HconfConfigs.Comment("file version - do not change")
		public int DO_NOT_CHANGE_IT = 3;
		@HconfConfigs.Comment("currently selected modpack; empty = none")
		public String selectedModpackId = "";
		@HconfConfigs.Comment("switch to the server's current modpack on launch")
		public boolean updateSelectedModpackOnLaunch = true;
		@HconfConfigs.Comment("let the mod update itself")
		public boolean selfUpdater = false;
		@HconfConfigs.Comment("keep the automodpack version in sync with the server's")
		public boolean syncAutoModpackVersion = true;
		@HconfConfigs.Comment("keep the loader version in sync with the server's")
		public boolean syncLoaderVersion = true;
		@HconfConfigs.Comment("play the modpack's music")
		public boolean playMusic = true;
		@HconfConfigs.Comment("show the modpack settings button")
		public boolean showModpackSettingsButton = true;
		@HconfConfigs.Comment("mods pinned to stay installed regardless of modpack changes")
		public List<String> pinnedModIds = new ArrayList<>();

		public ClientConfigFieldsV3() {}

		public ClientConfigFieldsV3(ClientConfigFieldsV3 source) {
			this.selectedModpackId = source.selectedModpackId;
			this.updateSelectedModpackOnLaunch = source.updateSelectedModpackOnLaunch;
			this.selfUpdater = source.selfUpdater;
			this.syncAutoModpackVersion = source.syncAutoModpackVersion;
			this.syncLoaderVersion = source.syncLoaderVersion;
			this.playMusic = source.playMusic;
			this.showModpackSettingsButton = source.showModpackSettingsButton;
			this.pinnedModIds = new ArrayList<>(PinnedMods.normalize(source.pinnedModIds));
		}

		public ClientConfigFieldsV3 withSelectedModpackId(String selectedModpackId) {
			ClientConfigFieldsV3 copy = new ClientConfigFieldsV3(this);
			copy.selectedModpackId = selectedModpackId;
			return copy;
		}

		/** Blank is the unset sentinel: no modpack selected yet; anything else must be a valid ID. */
		public boolean hasSelectedModpack() {
			return selectedModpackId != null && !selectedModpackId.isBlank();
		}

		public ClientConfigFieldsV3 withPlayMusic(boolean playMusic) {
			ClientConfigFieldsV3 copy = new ClientConfigFieldsV3(this);
			copy.playMusic = playMusic;
			return copy;
		}

		public ClientConfigFieldsV3 withPinnedModIds(List<String> pinnedModIds) {
			ClientConfigFieldsV3 copy = new ClientConfigFieldsV3(this);
			copy.pinnedModIds = new ArrayList<>(PinnedMods.normalize(pinnedModIds));
			return copy;
		}

		/** Reapplies a pending plan only to settings that the user has not changed since planning. */
		public ClientConfigFieldsV3 rebase(ClientConfigFieldsV3 expected, ClientConfigFieldsV3 planned, boolean mayUpdateSelectedModpack) {
			Objects.requireNonNull(expected, "expected config");
			Objects.requireNonNull(planned, "planned config");
			ClientConfigFieldsV3 rebased = new ClientConfigFieldsV3(this);
			if (mayUpdateSelectedModpack && Objects.equals(selectedModpackId, expected.selectedModpackId)) rebased.selectedModpackId = planned.selectedModpackId;
			if (updateSelectedModpackOnLaunch == expected.updateSelectedModpackOnLaunch)
				rebased.updateSelectedModpackOnLaunch = planned.updateSelectedModpackOnLaunch;
			if (selfUpdater == expected.selfUpdater) rebased.selfUpdater = planned.selfUpdater;
			if (syncAutoModpackVersion == expected.syncAutoModpackVersion) rebased.syncAutoModpackVersion = planned.syncAutoModpackVersion;
			if (syncLoaderVersion == expected.syncLoaderVersion) rebased.syncLoaderVersion = planned.syncLoaderVersion;
			if (playMusic == expected.playMusic) rebased.playMusic = planned.playMusic;
			if (showModpackSettingsButton == expected.showModpackSettingsButton) rebased.showModpackSettingsButton = planned.showModpackSettingsButton;
			if (Objects.equals(PinnedMods.normalize(pinnedModIds), PinnedMods.normalize(expected.pinnedModIds)))
				rebased.pinnedModIds = new ArrayList<>(PinnedMods.normalize(planned.pinnedModIds));
			return rebased;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof ClientConfigFieldsV3 other)) return false;
			return updateSelectedModpackOnLaunch == other.updateSelectedModpackOnLaunch && selfUpdater == other.selfUpdater
					&& syncAutoModpackVersion == other.syncAutoModpackVersion && syncLoaderVersion == other.syncLoaderVersion && playMusic == other.playMusic
					&& showModpackSettingsButton == other.showModpackSettingsButton && Objects.equals(selectedModpackId, other.selectedModpackId)
					&& Objects.equals(PinnedMods.normalize(pinnedModIds), PinnedMods.normalize(other.pinnedModIds));
		}

		@Override
		public int hashCode() {
			return Objects.hash(selectedModpackId, updateSelectedModpackOnLaunch, selfUpdater, syncAutoModpackVersion, syncLoaderVersion, playMusic, showModpackSettingsButton, PinnedMods.normalize(pinnedModIds));
		}

		/** Canonical with {@link #equals}: normalized pins, no file-version field. */
		@Override
		public String toString() {
			return "ClientConfigFieldsV3[selectedModpackId=" + selectedModpackId + ", updateSelectedModpackOnLaunch=" + updateSelectedModpackOnLaunch + ", selfUpdater=" + selfUpdater
					+ ", syncAutoModpackVersion=" + syncAutoModpackVersion + ", syncLoaderVersion=" + syncLoaderVersion + ", playMusic=" + playMusic
					+ ", showModpackSettingsButton=" + showModpackSettingsButton + ", pinnedModIds=" + PinnedMods.normalize(pinnedModIds) + "]";
		}
	}
}
