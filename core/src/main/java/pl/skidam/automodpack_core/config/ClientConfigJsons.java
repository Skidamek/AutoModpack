package pl.skidam.automodpack_core.config;

import java.util.Objects;

public class ClientConfigJsons {

	public static class ClientConfigFieldsV3 {
		public int DO_NOT_CHANGE_IT = 3; // file version
		public String selectedModpackId = "";
		public boolean updateSelectedModpackOnLaunch = true;
		public boolean selfUpdater = false;
		public boolean syncAutoModpackVersion = true;
		public boolean syncLoaderVersion = true;
		public boolean playMusic = true;

		public ClientConfigFieldsV3() {}

		public ClientConfigFieldsV3(ClientConfigFieldsV3 source) {
			this.selectedModpackId = source.selectedModpackId;
			this.updateSelectedModpackOnLaunch = source.updateSelectedModpackOnLaunch;
			this.selfUpdater = source.selfUpdater;
			this.syncAutoModpackVersion = source.syncAutoModpackVersion;
			this.syncLoaderVersion = source.syncLoaderVersion;
			this.playMusic = source.playMusic;
		}

		public ClientConfigFieldsV3 withSelectedModpackId(String selectedModpackId) {
			ClientConfigFieldsV3 copy = new ClientConfigFieldsV3(this);
			copy.selectedModpackId = selectedModpackId;
			return copy;
		}

		public ClientConfigFieldsV3 withPlayMusic(boolean playMusic) {
			ClientConfigFieldsV3 copy = new ClientConfigFieldsV3(this);
			copy.playMusic = playMusic;
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
			return rebased;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof ClientConfigFieldsV3 other)) return false;
			return updateSelectedModpackOnLaunch == other.updateSelectedModpackOnLaunch && selfUpdater == other.selfUpdater
					&& syncAutoModpackVersion == other.syncAutoModpackVersion && syncLoaderVersion == other.syncLoaderVersion && playMusic == other.playMusic
					&& Objects.equals(selectedModpackId, other.selectedModpackId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(selectedModpackId, updateSelectedModpackOnLaunch, selfUpdater, syncAutoModpackVersion, syncLoaderVersion, playMusic);
		}
	}
}
