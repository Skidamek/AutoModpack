package pl.skidam.automodpack_core.config;

import java.util.List;

public class StorageJsons {

	public static class DataRootFields {
		public String root = "";
		public String ownerId = "";
		public String ownerPathHash = "";
		public String ownerPath = "";
	}

	public static class ObjectOwnershipFields {
		public String ownerId = "";
		public String component = "";
		public String ownerPath = "";
		public List<String> objectHashes = List.of();
	}
}
