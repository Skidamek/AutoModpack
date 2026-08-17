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
