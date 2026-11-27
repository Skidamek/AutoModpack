package pl.skidam.automodpack_core.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.annotations.SerializedName;

public class SelectionJsons {

	public static class ClientSelectionStoreFields {
		public int DO_NOT_CHANGE_IT = 2; // file version
		public Map<String, ModpackSelection> selections = new HashMap<>();

		public static class ModpackSelection {
			public Set<String> enabled = new HashSet<>();
			public Set<String> disabled = new HashSet<>();
			@SerializedName("platform")
			public String platform;

			public ModpackSelection() {}

			public ModpackSelection(Set<String> enabled, Set<String> disabled, String platform) {
				this.enabled = enabled;
				this.disabled = disabled;
				this.platform = platform;
			}
		}
	}
}
