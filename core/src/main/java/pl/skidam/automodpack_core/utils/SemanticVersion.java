package pl.skidam.automodpack_core.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mod version split into numeric and alphabetical runs, ordered the way mod loaders order them: numbers
 * numerically with missing components as zero, alphabetical runs case-insensitively, a known pre-release
 * label (dev, snapshot, a/alpha, b/beta, pre/preview, rc) below the plain release, and any other trailing
 * runs above it - deliberate, because a suffixed variant ({@code 1.0.0-FABRIC}) usually denotes a newer
 * build of the same release, while the ladder carries the labels whose ecosystem meaning is strictly
 * older-than-release. Numeric first runs rank above the release too, and cannot be demoted the way semver
 * demotes {@code 1.0.0-1}: parsing loses the separator, so {@code 1.0.0-1} and {@code 1.0.0.1} are the same
 * version here, and a four-component version ({@code 14.23.5.2859}) must outrank its three-component base.
 * Build metadata (+...) never affects ordering.
 */
public record SemanticVersion(long major, long minor, long patch, List<Part> tail) implements Comparable<SemanticVersion> {

	// Regex for basic X.Y.Z(-PRERELEASE)?
	private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(.+))?$");

	private static final Map<String, Integer> PRE_RELEASE_RUNGS = Map.of("dev", 1, "snapshot", 2, "a", 3, "alpha", 3, "b", 4, "beta", 4, "pre", 5, "preview", 5, "rc", 6);
	private static final int RELEASE_RUNG = 7;

	/** One maximal run of the version: a number or a case-folded alphabetical run; exactly one of the two is present. */
	public record Part(long number, String alpha) {
		public static Part numeric(long number) {
			return new Part(number, null);
		}

		public static Part alphabetical(String alpha) {
			return new Part(0, alpha);
		}

		public boolean numeric() {
			return alpha == null;
		}
	}

	public SemanticVersion {
		tail = List.copyOf(tail);
	}

	/** Parses the standard space (X.Y.Z with an optional -label); anything else is rejected. */
	public static SemanticVersion parse(String versionString) {
		if (versionString == null || versionString.isBlank()) throw new IllegalArgumentException("Version cannot be empty");
		Matcher matcher = VERSION_PATTERN.matcher(versionString.trim());
		if (!matcher.matches()) throw new IllegalArgumentException("Invalid version format: " + versionString);
		return parseOrNull(versionString);
	}

	/**
	 * Parses any non-blank string: a leading v/V before a digit is dropped, build metadata (+...) is stripped from
	 * ordering, and the remainder splits into numeric and alphabetical runs; null only for null or blank input.
	 */
	public static SemanticVersion parseOrNull(String versionString) {
		if (versionString == null || versionString.isBlank()) return null;
		String version = versionString.trim();
		if (version.length() > 1 && (version.charAt(0) == 'v' || version.charAt(0) == 'V') && isAsciiDigit(version.charAt(1))) version = version.substring(1);
		int metadata = version.indexOf('+');
		if (metadata >= 0) version = version.substring(0, metadata);
		List<Part> parts = runs(version);
		long[] core = new long[3];
		int taken = 0;
		List<Part> tail = new ArrayList<>();
		for (Part part : parts) {
			if (taken < 3 && part.numeric()) core[taken++] = part.number();
			else tail.add(part);
		}
		return new SemanticVersion(core[0], core[1], core[2], tail);
	}

	/** Splits into maximal numeric and alphabetical runs; every other character separates runs. */
	private static List<Part> runs(String version) {
		List<Part> parts = new ArrayList<>();
		Long number = null;
		StringBuilder alpha = null;
		for (int index = 0; index <= version.length(); index++) {
			// ASCII only: Character.isDigit also answers Unicode decimal digits, whose subtraction from '0' would silently produce a garbage numeric run.
			boolean digit = index < version.length() && isAsciiDigit(version.charAt(index));
			boolean letter = index < version.length() && Character.isLetter(version.charAt(index));
			if (digit) {
				if (alpha != null) {
					parts.add(Part.alphabetical(alpha.toString()));
					alpha = null;
				}
				number = (number == null ? 0 : number) * 10 + (version.charAt(index) - '0');
				// Once the accumulation overflows it stays negative until it is flushed, so clamping here keeps every longer run at the ceiling instead of letting it wrap back into range.
				if (number < 0) number = Long.MAX_VALUE;
			} else if (letter) {
				if (number != null) {
					parts.add(Part.numeric(number));
					number = null;
				}
				if (alpha == null) alpha = new StringBuilder();
				alpha.append(Character.toLowerCase(version.charAt(index)));
			} else {
				if (number != null) {
					parts.add(Part.numeric(number));
					number = null;
				}
				if (alpha != null) {
					parts.add(Part.alphabetical(alpha.toString()));
					alpha = null;
				}
			}
		}
		return parts;
	}

	/** Whether the version carries no known pre-release label; final/release spell it explicitly. */
	public boolean isStable() {
		return rung() == RELEASE_RUNG;
	}

	/**
	 * Deterministic total winner order over raw version strings: parsed versions compare through
	 * {@link #compareTo}, unparseable (blank) ones only ever tie with themselves and compare as raw strings.
	 * Callers break remaining ties by path.
	 */
	public static int compareVersionStrings(String left, String right) {
		SemanticVersion parsedLeft = parseOrNull(left);
		SemanticVersion parsedRight = parseOrNull(right);
		if (parsedLeft != null && parsedRight != null) return parsedLeft.compareTo(parsedRight);
		if (parsedLeft != null) return 1;
		if (parsedRight != null) return -1;
		return String.valueOf(left).compareTo(String.valueOf(right));
	}

	/**
	 * The one winner-election every duplicate resolution shares - the nested-jar scanner and the update planner's
	 * duplicate disposition alike: the higher version wins, and an equal version breaks the tie on the
	 * lexicographically smaller name, so the same candidates elect the same jar wherever they meet.
	 */
	public static boolean wins(String challengerVersion, String challengerName, String incumbentVersion, String incumbentName) {
		int comparison = compareVersionStrings(challengerVersion, incumbentVersion);
		if (comparison != 0) return comparison > 0;
		return challengerName.compareTo(incumbentName) < 0;
	}

	private int rung() {
		if (tail.isEmpty()) return RELEASE_RUNG;
		Part first = tail.get(0);
		return first.numeric() ? RELEASE_RUNG : PRE_RELEASE_RUNGS.getOrDefault(first.alpha(), RELEASE_RUNG);
	}

	@Override
	public int compareTo(SemanticVersion other) {
		if (major != other.major) return Long.compare(major, other.major);
		if (minor != other.minor) return Long.compare(minor, other.minor);
		if (patch != other.patch) return Long.compare(patch, other.patch);
		int rung = rung(), otherRung = other.rung();
		if (rung != otherRung) return Integer.compare(rung, otherRung);
		for (int index = 0; index < Math.min(tail.size(), other.tail.size()); index++) {
			Part left = tail.get(index), right = other.tail.get(index);
			if (left.numeric() != right.numeric()) return left.numeric() ? 1 : -1;
			int comparison = left.numeric() ? Long.compare(left.number(), right.number()) : left.alpha().compareTo(right.alpha());
			if (comparison != 0) return comparison;
		}
		return Integer.compare(tail.size(), other.tail.size());
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
		for (Part part : tail) builder.append('.').append(part.numeric() ? part.number() : part.alpha());
		return builder.toString();
	}

	private static boolean isAsciiDigit(char value) {
		return value >= '0' && value <= '9';
	}
}
