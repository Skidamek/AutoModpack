package pl.skidam.automodpack_core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.loader.PinnedMods;

public class ClientConfigJsons {

	public static class ClientConfigFieldsV3 {
		@ReconfConfigs.Comment("fetch and apply the pack during launch")
		@SerializedName("update-selected-modpack-on-launch")
		public boolean updateSelectedModpackOnLaunch = true;
		@SerializedName("self-updater")
		public boolean selfUpdater = false;
		@SerializedName("sync-auto-modpack-version")
		public boolean syncAutoModpackVersion = true;
		@SerializedName("sync-loader-version")
		public boolean syncLoaderVersion = true;
		@SerializedName("play-music")
		public boolean playMusic = true;
		@SerializedName("show-modpack-settings-button")
		public boolean showModpackSettingsButton = true;
		@ReconfConfigs.Comment("mod ids that keep loading from this instance's own mods folder")
		@SerializedName("pinned-mod-ids")
		public List<String> pinnedModIds = new ArrayList<>();

		public ClientConfigFieldsV3() {}

		public ClientConfigFieldsV3(ClientConfigFieldsV3 source) {
			this.updateSelectedModpackOnLaunch = source.updateSelectedModpackOnLaunch;
			this.selfUpdater = source.selfUpdater;
			this.syncAutoModpackVersion = source.syncAutoModpackVersion;
			this.syncLoaderVersion = source.syncLoaderVersion;
			this.playMusic = source.playMusic;
			this.showModpackSettingsButton = source.showModpackSettingsButton;
			this.pinnedModIds = new ArrayList<>(PinnedMods.normalize(source.pinnedModIds));
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
		public ClientConfigFieldsV3 rebase(ClientConfigFieldsV3 expected, ClientConfigFieldsV3 planned) {
			Objects.requireNonNull(expected, "expected config");
			Objects.requireNonNull(planned, "planned config");
			ClientConfigFieldsV3 rebased = new ClientConfigFieldsV3(this);
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
					&& showModpackSettingsButton == other.showModpackSettingsButton
					&& Objects.equals(PinnedMods.normalize(pinnedModIds), PinnedMods.normalize(other.pinnedModIds));
		}

		@Override
		public int hashCode() {
			return Objects.hash(updateSelectedModpackOnLaunch, selfUpdater, syncAutoModpackVersion, syncLoaderVersion, playMusic, showModpackSettingsButton, PinnedMods.normalize(pinnedModIds));
		}

		@Override
		public String toString() {
			return "ClientConfigFieldsV3[updateSelectedModpackOnLaunch=" + updateSelectedModpackOnLaunch + ", selfUpdater=" + selfUpdater
					+ ", syncAutoModpackVersion=" + syncAutoModpackVersion + ", syncLoaderVersion=" + syncLoaderVersion + ", playMusic=" + playMusic
					+ ", showModpackSettingsButton=" + showModpackSettingsButton + ", pinnedModIds=" + PinnedMods.normalize(pinnedModIds) + "]";
		}
	}

	/** Machine-owned follow pointer: which pack this instance follows. */
	public static class SelectedModpackFields {
		public String modpackId = "";
	}
}
