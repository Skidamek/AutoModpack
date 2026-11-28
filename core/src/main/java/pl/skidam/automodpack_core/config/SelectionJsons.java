package pl.skidam.automodpack_core.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;

public class SelectionJsons {

	public static class ClientSelectionStoreFields {
		public int DO_NOT_CHANGE_IT = 2; // file version
		public Map<String, SelectionFields> selections = new HashMap<>();
	}

	/** Shared persisted selection intent DTO across client store, active pointer, instance trees, and update transactions. */
	public static class SelectionFields {
		public List<String> enabled = new ArrayList<>();
		public List<String> disabled = new ArrayList<>();
		@SerializedName("platform")
		public String platform;

		public SelectionFields() {}

		public SelectionFields(List<String> enabled, List<String> disabled, String platform) {
			this.enabled = enabled;
			this.disabled = disabled;
			this.platform = platform;
		}

		public static SelectionFields from(SelectionIntent intent) {
			if (intent == null) return null;
			return new SelectionFields(new ArrayList<>(intent.enabled()), new ArrayList<>(intent.disabled()),
					intent.platform() == null ? null : intent.platform().id());
		}

		public SelectionIntent toIntent() {
			ClientPlatform parsed = platform == null || platform.isBlank() ? null : ClientPlatform.parse(platform);
			return new SelectionIntent(enabled == null ? List.of() : enabled, disabled == null ? List.of() : disabled, parsed);
		}

		public SelectionFields copy() {
			return new SelectionFields(enabled == null ? null : new ArrayList<>(enabled), disabled == null ? null : new ArrayList<>(disabled), platform);
		}

		public boolean isCanonical() {
			if (!isCanonicalList(enabled) || !isCanonicalList(disabled)) return false;
			for (String id : enabled) if (disabled.contains(id)) return false;
			if (platform != null) {
				try {
					if (!ClientPlatform.parse(platform).id().equals(platform)) return false;
				} catch (RuntimeException e) {
					return false;
				}
			}
			return true;
		}

		private static boolean isCanonicalList(List<String> values) {
			if (values == null) return false;
			for (String value : values) if (value == null || value.isBlank()) return false;
			return values.equals(values.stream().distinct().sorted().toList());
		}
	}
}
