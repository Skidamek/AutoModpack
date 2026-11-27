package pl.skidam.automodpack_core.config;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.modpack.group.CategoryPickMode;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;

public class ModpackJsons {

	/** The immutable policy document of one generation: pack metadata, categories, and every group's files and rules. */
	public static class CompleteModpackContentFields {
		public String modpackId = "";
		public String modpackName = "";
		public String automodpackVersion = "";
		public String loader = "";
		public String loaderVersion = "";
		public String mcVersion = "";
		public Map<String, CategoryFields> categories = Map.of();
		public Map<String, ModpackGroupFields> groups = Map.of();

		public static class CategoryFields {
			public String name = "";
			public String description = "";
			public CategoryPickMode pick = CategoryPickMode.ANY;
		}

		public static class ModpackGroupFields {
			public String name = "";
			public String description = "";
			public String category = "";
			public GroupSelectionMode selection;
			public Set<String> requires = Set.of();
			public Set<String> compatiblePlatforms = Set.of();
			public Map<String, GroupFileFields> files = Map.of();
		}

		public static class GroupFileFields {
			public String size = "";
			public boolean editable;
			public String sha1 = "";
			public String murmur;

			public GroupFileFields() {}

			public GroupFileFields(String size, boolean editable, String sha1, String murmur) {
				this.size = size;
				this.editable = editable;
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
		public String contentToken = "";
		public String policySha1 = "";

		public ModpackContentFields(Set<ModpackContentItem> list) {
			this.list = list;
		}

		public ModpackContentFields() {
			this.list = Set.of();
		}

		public static class ModpackContentItem {
			public final String file;
			public final long size;
			public final boolean editable;
			public final String sha1;
			public final String murmur;

			public ModpackContentItem(String file, long size, boolean editable, String sha1, String murmur) {
				this.file = file;
				this.size = size;
				this.editable = editable;
				this.sha1 = sha1;
				this.murmur = murmur;
			}

			@Override
			public String toString() {
				return String.format(Locale.ROOT, "ModpackContentItems(file=%s, size=%s, editable=%s, sha1=%s, murmur=%s)", file, size, editable, sha1, murmur);
			}

			@Override
			public boolean equals(Object obj) {
				if (this == obj) return true;
				if (obj == null || getClass() != obj.getClass()) return false;
				ModpackContentItem that = (ModpackContentItem) obj;
				return editable == that.editable && size == that.size
						&& Objects.equals(file, that.file)
						&& Objects.equals(sha1, that.sha1) && Objects.equals(murmur, that.murmur);
			}

			@Override
			public int hashCode() {
				return Objects.hash(file, size, editable, sha1, murmur);
			}
		}

	}
}
