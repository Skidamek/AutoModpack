package pl.skidam.automodpack_core.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ModpackJsons {

	public static class CompleteModpackContentFields {
		public String modpackId = "";
		public String modpackName = "";
		public String automodpackVersion = "";
		public String loader = "";
		public String loaderVersion = "";
		public String mcVersion = "";
		public Map<String, ModpackGroupFields> groups = Map.of();
		public GenerationJsons.OwnershipLedgerFields ownershipLedger = new GenerationJsons.OwnershipLedgerFields();
		public GenerationFields generation;
		public List<PatchNotesHistoryEntryFields> patchNotesHistory = List.of();

		public static class GenerationFields {
			public int schemaVersion;
			public String generationId = "";
			public String parentGenerationId = "";
			public String createdAt = "";
			public String stateDigest = "";
			public String ledgerDigest = "";
			public String patchNotes = "";
			public String patchNotesDigest = "";
			public String rollbackTargetGenerationId = "";
		}

		public static class PatchNotesHistoryEntryFields {
			public int schemaVersion;
			public String generationId = "";
			public String parentGenerationId = "";
			public String createdAt = "";
			public String patchNotes = "";
			public String patchNotesDigest = "";
		}

		public static class ModpackGroupFields {
			public String displayName = "";
			public String description = "";
			public String tag = "";
			public boolean required;
			public boolean defaultSelected;
			public Set<String> breaksWith = Set.of();
			public Set<String> requires = Set.of();
			public Set<String> compatiblePlatforms = Set.of();
			public Map<String, GroupFileFields> files = Map.of();
		}

		public static class GroupFileFields {
			public String size = "";
			public String type = "";
			public boolean editable;
			public boolean overwriteEditable;
			public String sha1 = "";
			public String murmur;

			public GroupFileFields() {}

			public GroupFileFields(String size, String type, boolean editable, boolean overwriteEditable, String sha1, String murmur) {
				this.size = size;
				this.type = type;
				this.editable = editable;
				this.overwriteEditable = overwriteEditable;
				this.sha1 = sha1;
				this.murmur = murmur;
			}
		}
	}

	public static class ModpackContentFields {
		public String modpackId = "";
		public String modpackName = "";
		public String automodpackVersion = "";
		public String loader = "";
		public String loaderVersion = "";
		public String mcVersion = "";
		public Set<ModpackContentItem> list;
		public Set<String> selectedGroups = Set.of();
		public GenerationJsons.OwnershipLedgerFields ownershipLedger = new GenerationJsons.OwnershipLedgerFields();
		public String targetGenerationId = "";
		public String parentGenerationId = "";
		public String stateDigest = "";

		public ModpackContentFields(Set<ModpackContentItem> list) {
			this.list = list;
		}

		public ModpackContentFields() {
			this.list = Set.of();
		}

		public static class ModpackContentItem {
			public final String file;
			public final String size;
			public final String type;
			public final boolean editable;
			public final boolean overwriteEditable;
			public final String sha1;
			public final String murmur;

			public ModpackContentItem(String file, String size, String type, boolean editable, boolean overwriteEditable, String sha1, String murmur) {
				this.file = file;
				this.size = size;
				this.type = type;
				this.editable = editable;
				this.overwriteEditable = overwriteEditable;
				this.sha1 = sha1;
				this.murmur = murmur;
			}

			@Override
			public String toString() {
				return String.format("ModpackContentItems(file=%s, size=%s, type=%s, editable=%s, sha1=%s, murmur=%s)", file, size, type, editable, sha1, murmur);
			}

			@Override
			public boolean equals(Object obj) {
				if (this == obj) return true;
				if (obj == null || getClass() != obj.getClass()) return false;
				ModpackContentItem that = (ModpackContentItem) obj;
				return editable == that.editable && overwriteEditable == that.overwriteEditable
						&& Objects.equals(file, that.file) && Objects.equals(size, that.size) && Objects.equals(type, that.type)
						&& Objects.equals(sha1, that.sha1) && Objects.equals(murmur, that.murmur);
			}

			@Override
			public int hashCode() {
				return Objects.hash(file, size, type, editable, overwriteEditable, sha1, murmur);
			}
		}

	}
}
