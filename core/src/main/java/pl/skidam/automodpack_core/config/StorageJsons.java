package pl.skidam.automodpack_core.config;

import java.util.List;

public class StorageJsons {

	public static class ObjectOwnershipFields {
		public String ownerId = "";
		public String component = "";
		public String ownerPath = "";
		public List<String> objectHashes = List.of();
	}

	/** The durable record of a pending AutoModpack self-update swap, game-directory relative. */
	public static class SelfUpdateFields {
		public String currentPath = "";
		public String targetPath = "";
		public String targetSha1 = "";
		public long targetSize = -1;
		public String currentSha1 = "";
	}
}
