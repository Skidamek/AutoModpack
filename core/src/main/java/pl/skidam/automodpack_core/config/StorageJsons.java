package pl.skidam.automodpack_core.config;

import java.util.List;

public class StorageJsons {

	public static class DataRootFields {
		public String root = "";
		public boolean shared;
		public String ownerId = "";
	}

	public static class ObjectOwnershipFields {
		public String ownerId = "";
		public String component = "";
		public List<String> objectHashes = List.of();
	}
}
