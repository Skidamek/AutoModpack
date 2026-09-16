package pl.skidam.automodpack_core.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SelectionJsons {

	public static class ClientSelectionStoreFields {
		public int DO_NOT_CHANGE_IT = 1; // file version
		public Map<String, ModpackSelection> selections = new HashMap<>();

		public static class ModpackSelection {
			public Set<String> requestedGroups = new HashSet<>();
			public Set<String> requestedCategories = new HashSet<>();
			public Set<String> excludedGroups = new HashSet<>();

			public ModpackSelection() {}

			public ModpackSelection(Set<String> requestedGroups) {
				this(requestedGroups, Set.of());
			}

			public ModpackSelection(Set<String> requestedGroups, Set<String> excludedGroups) {
				this(requestedGroups, Set.of(), excludedGroups);
			}

			public ModpackSelection(Set<String> requestedGroups, Set<String> requestedCategories, Set<String> excludedGroups) {
				this.requestedGroups = requestedGroups;
				this.requestedCategories = requestedCategories;
				this.excludedGroups = excludedGroups;
			}
		}
	}
}
