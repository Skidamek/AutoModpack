package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class FileTreeScanner {

	// Volatile snapshot pattern allows non-blocking reads during scanning
	private volatile Map<String, Path> matchedPaths = Map.of();
	private final Object lock = new Object();

	private final List<PathMatcher> whitelistMatchers = new ArrayList<>();
	private final List<PathMatcher> blacklistMatchers = new ArrayList<>();
	private final List<PathMatcher> blacklistedSubtreeMatchers = new ArrayList<>();
	private final List<PruningRule> pruningRules = new ArrayList<>();
	private final Set<Path> startDirectories;
	private final FileSystem fs;
	private final boolean isCaseInsensitive;

	public FileTreeScanner(Set<String> rules, Set<Path> startDirectories) {
		this(rules, startDirectories, FileSystems.getDefault());
	}

	public FileTreeScanner(Set<String> rules, Set<Path> startDirectories, FileSystem fs) {
		this.startDirectories = startDirectories != null ? startDirectories : Set.of();
		this.isCaseInsensitive = FileSystemCapabilities.isCaseInsensitive(fs);
		this.fs = fs;
		parseRules(rules);
	}

	public Map<String, Path> getMatchedPaths() {
		return matchedPaths;
	}

	public boolean hasMatch(String pathStr) {
		return pathStr != null && matchedPaths.containsKey(pathStr);
	}

	public boolean matches(String pathStr) {
		if (pathStr == null || pathStr.isBlank()) return false;
		String relativePath = pathStr.startsWith("/") ? pathStr.substring(1) : pathStr;
		return matches(fs.getPath(relativePath));
	}

	private boolean matches(Path path) {
		return matchesAny(whitelistMatchers, path) && !matchesAny(blacklistMatchers, path);
	}

	public void scan() {
		synchronized (lock) {
			if (whitelistMatchers.isEmpty()) return;

			Map<String, Path> localMatches = new HashMap<>(512);
			var options = EnumSet.of(FileVisitOption.FOLLOW_LINKS);

			for (Path startDir : startDirectories) {
				if (startDir == null || !Files.exists(startDir)) continue;

				Path absStartDir = startDir.toAbsolutePath().normalize();
				try {
					// Integer.MAX_VALUE depth used; pruning is handled manually in preVisitDirectory
					Files.walkFileTree(startDir, options, Integer.MAX_VALUE, new Visitor(startDir, absStartDir, localMatches));
				} catch (IOException e) {
					LOGGER.error("Error walking directory: {}", startDir, e);
				}
			}
			// Atomic swap for thread safety
			this.matchedPaths = Map.copyOf(localMatches);
		}
	}

	private String formatOutputKey(Path relativePath) {
		// Enforce forward slashes for cross-platform consistency in the output map
		String pathStr = relativePath.toString().replace(fs.getSeparator(), "/");
		return pathStr.startsWith("/") ? pathStr : "/" + pathStr;
	}

	// =================================================================================
	// VISITOR IMPLEMENTATION
	// =================================================================================

	private class Visitor extends SimpleFileVisitor<Path> {
		private final Path startDir;
		private final Path absStartDir;
		private final Map<String, Path> targetMap;

		Visitor(Path startDir, Path absStartDir, Map<String, Path> targetMap) {
			this.startDir = startDir;
			this.absStartDir = absStartDir;
			this.targetMap = targetMap;
		}

		@Override
		public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
			if (dir.equals(startDir)) return FileVisitResult.CONTINUE;

			// Security: Prevent Symlink Jailbreaking
			try {
				if (attrs.isSymbolicLink()) {
					Path absDir = dir.toAbsolutePath().normalize();
					if (!absDir.startsWith(absStartDir)) return FileVisitResult.SKIP_SUBTREE;
				}
			} catch (Exception e) {
				return FileVisitResult.SKIP_SUBTREE;
			}

			Path relativeDir = startDir.relativize(dir);
			if (matchesAny(blacklistedSubtreeMatchers, relativeDir)) return FileVisitResult.SKIP_SUBTREE;

			// Optimization: Pruning check to skip irrelevant subtrees
			Path fileNamePath = dir.getFileName();
			if (fileNamePath != null && !couldMatchStructure(fileNamePath.toString(), relativeDir.getNameCount())) return FileVisitResult.SKIP_SUBTREE;

			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
			Path relative = startDir.relativize(file);
			if (matches(relative)) targetMap.put(formatOutputKey(relative), file);
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(Path file, IOException exc) {
			if (exc instanceof FileSystemLoopException) LOGGER.warn("Cycle: {}", file);
			else if (exc instanceof AccessDeniedException) LOGGER.info("Access denied: {}", file);
			else LOGGER.error("Visit failed: {} ({})", file, exc);
			return FileVisitResult.CONTINUE;
		}

		private boolean couldMatchStructure(String dirName, int depth) {
			if (pruningRules.isEmpty()) return false;
			int size = pruningRules.size();
			for (int i = 0; i < size; i++) {
				if (pruningRules.get(i).allows(dirName, depth)) return true;
			}
			return false;
		}
	}

	private static boolean matchesAny(List<PathMatcher> matchers, Path path) {
		int size = matchers.size();
		for (int i = 0; i < size; i++) {
			if (matchers.get(i).matches(path)) return true;
		}
		return false;
	}

	// =================================================================================
	// RULE PARSING & GLOB LOGIC
	// =================================================================================

	private void parseRules(Set<String> rawRules) {
		if (rawRules == null) return;
		for (String rule : rawRules) {
			if (rule == null || rule.isBlank()) continue;

			boolean isBlacklist = rule.startsWith("!");
			// Strips '!' but preserves other backslashes for escaping
			String clean = isBlacklist ? rule.substring(1) : rule;

			if (clean.startsWith("/")) clean = clean.substring(1);
			while (clean.contains("**/**")) clean = clean.replace("**/**", "**");

			try {
				PathMatcher matcher = fs.getPathMatcher("glob:" + clean);
				if (isBlacklist) {
					blacklistMatchers.add(matcher);
					if (clean.endsWith("/**")) {
						String subtreePattern = clean.substring(0, clean.length() - 3);
						if (!subtreePattern.isEmpty()) blacklistedSubtreeMatchers.add(fs.getPathMatcher("glob:" + subtreePattern));
					}
				} else {
					whitelistMatchers.add(matcher);
					// Add collapsed variant for "foo/**/bar" -> "foo/bar" boundary cases
					if (clean.contains("/**/")) {
						String collapsed = clean.replace("/**/", "/");
						whitelistMatchers.add(fs.getPathMatcher("glob:" + collapsed));
					}
					// Generate Pruning Rule
					pruningRules.add(PruningRule.compile(clean, fs, isCaseInsensitive));
					// Allow traversal into root of recursive patterns
					if (clean.contains("/**/")) {
						String prefix = clean.substring(0, clean.indexOf("/**/"));
						if (!prefix.isEmpty()) pruningRules.add(PruningRule.compile(prefix, fs, isCaseInsensitive));
					}
				}
			} catch (Exception e) {
				LOGGER.error("Invalid glob: {}", rule, e);
			}
		}
	}

	/**
	 * Represents a decomposed Glob pattern to allow prefix matching on directory levels.
	 */
	private record PruningRule(GlobComponent[] components, int firstDoubleWildcardIdx) {
		static PruningRule compile(String pattern, FileSystem fs, boolean isCaseInsensitive) {
			String[] parts = pattern.split("/");
			var comps = new ArrayList<GlobComponent>(parts.length);
			int firstDouble = -1;

			for (String part : parts) {
				if (part.isEmpty()) continue;
				GlobComponent c = new GlobComponent(part, fs, isCaseInsensitive);
				comps.add(c);
				if (firstDouble == -1 && c.isDoubleWildcard) firstDouble = comps.size() - 1;
			}
			return new PruningRule(comps.toArray(new GlobComponent[0]), firstDouble);
		}

		boolean allows(String currentName, int depth) {
			// Depth 1-based in walker logic, converted to 0-based index
			if (firstDoubleWildcardIdx == -1 && (depth - 1) >= components.length) return false;
			int idx = depth - 1;
			// Always allow if we have passed a Double Wildcard (**)
			if (firstDoubleWildcardIdx != -1 && idx >= firstDoubleWildcardIdx) return true;
			if (idx >= 0 && idx < components.length) return components[idx].matches(currentName);
			return false;
		}
	}

	/**
	 * Handles matching for a single path segment.
	 * Uses hybrid approach: Custom fast-match for simple globs, JDK matcher for complex regex.
	 */
	private static class GlobComponent {
		final boolean isDoubleWildcard;
		final boolean isExact;
		final char[] pattern;
		final String patternStr;
		final PathMatcher complexMatcher;
		final boolean isCaseInsensitive;
		final FileSystem fs;

		GlobComponent(String patternStr, FileSystem fs, boolean isCaseInsensitive) {
			this.fs = fs;
			this.patternStr = patternStr;
			this.isCaseInsensitive = isCaseInsensitive;
			this.isDoubleWildcard = patternStr.equals("**");
			this.pattern = patternStr.toCharArray();

			boolean hasSimpleMeta = false;
			boolean hasComplex = false;
			for (char c : pattern) {
				if (c == '*' || c == '?') hasSimpleMeta = true;
				else if (c == '[' || c == '{' || c == '\\') {
					hasSimpleMeta = true;
					hasComplex = true;
				}
			}

			this.isExact = !hasSimpleMeta;
			// Delegate to JDK for brackets/braces/escapes
			this.complexMatcher = hasComplex ? fs.getPathMatcher("glob:" + patternStr) : null;
		}

		boolean matches(String text) {
			if (isDoubleWildcard) return true;
			if (complexMatcher != null) return complexMatcher.matches(fs.getPath(text));
			if (isExact) return isCaseInsensitive ? text.equalsIgnoreCase(patternStr) : text.equals(patternStr);
			return fastGlobMatch(pattern, text, isCaseInsensitive);
		}

		// Optimized greedy backtracking for '*' and '?'
		private static boolean fastGlobMatch(char[] p, String t, boolean caseInsensitive) {
			int tLen = t.length(), pLen = p.length;
			int tIdx = 0, pIdx = 0, starIdx = -1, matchIdx = -1;

			while (tIdx < tLen) {
				char tc = t.charAt(tIdx);
				char pc = pIdx < pLen ? p[pIdx] : '\0';

				boolean charsMatch = (pc == tc) || (caseInsensitive && Character.toLowerCase(pc) == Character.toLowerCase(tc));

				if (pIdx < pLen && (pc == '?' || charsMatch)) {
					pIdx++;
					tIdx++;
				} else if (pIdx < pLen && pc == '*') {
					starIdx = pIdx;
					matchIdx = tIdx;
					pIdx++;
				} else if (starIdx != -1) {
					pIdx = starIdx + 1;
					matchIdx++;
					tIdx = matchIdx;
				} else {
					return false;
				}
			}
			while (pIdx < pLen && p[pIdx] == '*') pIdx++;
			return pIdx == pLen;
		}
	}
}
