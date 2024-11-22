package pl.skidam.automodpack_loader_core.utils;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public class VersionParser {

	public static Integer[] parseVersion(String version) {
		String[] parts = version.split("\\.");
		List<Integer> result = new ArrayList<>();
		for (String part : parts) {
			if (part.matches("\\d+")) {
				result.add(Integer.parseInt(part));
			}
		}
		return result.toArray(new Integer[0]);
	}

	public static boolean isGreater(Integer[] v1, Integer[] v2) {
		return compare(v1, v2) > 0;
	}

	public static boolean isGreaterOrEqual(Integer[] v1, Integer[] v2) {
		return compare(v1, v2) >= 0;
	}

	public static boolean isLess(Integer[] v1, Integer[] v2) {
		return compare(v1, v2) < 0;
	}

	public static boolean isLessOrEqual(Integer[] v1, Integer[] v2) {
		return compare(v1, v2) <= 0;
	}

	private static int compare(Integer[] v1, Integer[] v2) {
		for (int i = 0; i < Math.min(v1.length, v2.length); i++) {
			if (v1[i] < v2[i]) {
				return -1;
			} else if (v1[i] > v2[i]) {
				return 1;
			}
		}
		return Integer.compare(v1.length, v2.length);
	}
}
