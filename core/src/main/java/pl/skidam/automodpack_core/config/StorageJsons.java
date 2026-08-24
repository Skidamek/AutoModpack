package pl.skidam.automodpack_core.config;

import java.util.List;

public class StorageJsons {

	public static class ObjectOwnershipFields {
		public String ownerId = "";
		public String component = "";
		public String ownerPath = "";
		public List<String> objectHashes = List.of();
	}
}
