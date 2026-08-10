package pl.skidam.automodpack_core.config;

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
	}
}
