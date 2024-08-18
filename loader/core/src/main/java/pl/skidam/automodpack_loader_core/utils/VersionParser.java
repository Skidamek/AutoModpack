package pl.skidam.automodpack_loader_core.utils;

public class VersionParser {
	// parse string versions like 0.15.11 or 0.16.0 and make them comparable

	public record Version(int major, int minor, int patch) {
		public static Version parse(String version) {
			String[] parts = version.split("\\.");
			return new Version(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		}
	}

	public static int compare(String version1, String version2) {
		Version v1 = Version.parse(version1);
		Version v2 = Version.parse(version2);
		if (v1.major() != v2.major()) {
			return Integer.compare(v1.major(), v2.major());
		}
		if (v1.minor() != v2.minor()) {
			return Integer.compare(v1.minor(), v2.minor());
		}
		return Integer.compare(v1.patch(), v2.patch());
	}
}
