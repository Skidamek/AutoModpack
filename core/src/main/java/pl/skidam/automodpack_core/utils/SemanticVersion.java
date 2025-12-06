package pl.skidam.automodpack_core.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Record for Semantic Versioning.
 * - Parses standard SemVer (1.0.0-beta.1)
 * - Parses non-standard (1.0.0-beta1)
 * - Correctly weights Release > RC > Beta > Alpha
 */
public record SemanticVersion(int major, int minor, int patch, String label, int preVersion) implements Comparable<SemanticVersion> {

    // Regex for basic X.Y.Z(-PRERELEASE)?
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(.+))?$");

    // Regex to split "beta.1", "beta1", "alpha-5", "rc2"
    private static final Pattern PRE_SPLIT_PATTERN = Pattern.compile("^([a-zA-Z]+)(?:[.\\-]?)(\\d+)?$");

    public static SemanticVersion parse(String versionString) {
        if (versionString == null || versionString.isBlank()) throw new IllegalArgumentException("Version cannot be empty");

        Matcher matcher = VERSION_PATTERN.matcher(versionString);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid version format: " + versionString);
        }

        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        String rawPre = matcher.group(4);

        if (rawPre == null) {
            // No label = Stable Release (e.g. 1.0.0)
            // We use MAX_VALUE to ensure Stable > any Prerelease
            return new SemanticVersion(major, minor, patch, "release", Integer.MAX_VALUE);
        }

        // Parse Prerelease (e.g., "beta1", "beta.1", "rc-2")
        Matcher preMatcher = PRE_SPLIT_PATTERN.matcher(rawPre);
        String label = rawPre;
        int preVer = 1; // Default to 1 if no number (e.g. "beta")

        if (preMatcher.find()) {
            label = preMatcher.group(1).toLowerCase();
            String numPart = preMatcher.group(2);
            if (numPart != null) {
                preVer = Integer.parseInt(numPart);
            }
        }

        return new SemanticVersion(major, minor, patch, label, preVer);
    }

    public boolean isStable() {
        return "release".equals(label);
    }

    /**
     * Determines priority of labels.
     * Higher number = "More Stable/Newer"
     */
    private int getLabelWeight() {
        return switch (label) {
            case "release" -> 100;
            case "rc", "pre" -> 50;  // Release Candidate / Pre-release
            case "beta" -> 30;
            case "alpha" -> 10;
            case "snapshot" -> 5;
            default -> 0; // Unknown labels are lowest priority
        };
    }

    @Override
    public int compareTo(SemanticVersion o) {
        if (this.major != o.major) return Integer.compare(this.major, o.major);
        if (this.minor != o.minor) return Integer.compare(this.minor, o.minor);
        if (this.patch != o.patch) return Integer.compare(this.patch, o.patch);

        // Version numbers are identical, check label weight (Stable > Beta)
        int thisWeight = this.getLabelWeight();
        int otherWeight = o.getLabelWeight();

        if (thisWeight != otherWeight) {
            return Integer.compare(thisWeight, otherWeight);
        }

        // Labels are same type (e.g. both beta), check the pre-version number (beta.2 > beta.1)
        return Integer.compare(this.preVersion, o.preVersion);
    }

    @Override
    public String toString() {
        if (isStable()) return String.format("%d.%d.%d", major, minor, patch);
        // Standardize output to always use dot separator (beta.1)
        return String.format("%d.%d.%d-%s.%d", major, minor, patch, label, preVersion);
    }
}