package pl.skidam.automodpack_core.modpack.candidate;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;

/** A set of glob path rules; every rule matches, exclusions are a separate rule set. */
public final class PathRuleSet {
	private final FileSystem fileSystem;
	private final List<CompiledRule> rules;

	public PathRuleSet(Collection<String> rules) {
		this(rules, FileSystems.getDefault());
	}

	PathRuleSet(Collection<String> rules, FileSystem fileSystem) {
		this.fileSystem = fileSystem;
		List<CompiledRule> compiled = new ArrayList<>();
		if (rules != null) for (String raw : new TreeSet<>(rules)) {
			if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Path rule is null or blank");
			if (raw.startsWith("!")) throw new IllegalArgumentException("Path rules cannot start with '!': exclusions live in their own rule set");
			String pattern = raw;
			while (pattern.startsWith("/")) pattern = pattern.substring(1);
			while (pattern.contains("**/**")) pattern = pattern.replace("**/**", "**");
			if (pattern.isBlank()) throw new IllegalArgumentException("Path rule is empty: " + raw);
			compiled.add(new CompiledRule(raw, compile(pattern)));
		}
		this.rules = List.copyOf(compiled);
	}

	public Decision evaluate(String path) {
		String logicalPath = LogicalPath.normalize(path);
		Path value = fileSystem.getPath(logicalPath);
		CompiledRule match = firstMatch(rules, value);
		return match == null ? Decision.UNMATCHED : new Decision(true, match.raw());
	}

	public boolean isEmpty() {
		return rules.isEmpty();
	}

	/** Returns the narrowest filesystem prefixes that can contain a matched path. */
	public Set<String> safeScanRoots() {
		if (rules.isEmpty()) return Set.of();
		Set<String> roots = new TreeSet<>();
		for (CompiledRule rule : rules) {
			String pattern = rule.raw();
			while (pattern.startsWith("/")) pattern = pattern.substring(1);
			StringBuilder literal = new StringBuilder();
			for (String component : pattern.split("/")) {
				if (component.isEmpty() || containsGlob(component)) break;
				if (literal.length() > 0) literal.append('/');
				literal.append(component);
			}
			roots.add(literal.toString());
		}
		return Set.copyOf(roots);
	}

	private static boolean containsGlob(String component) {
		return component.indexOf('*') >= 0 || component.indexOf('?') >= 0 || component.indexOf('[') >= 0 || component.indexOf('{') >= 0;
	}

	private List<PathMatcher> compile(String pattern) {
		try {
			List<PathMatcher> matchers = new ArrayList<>();
			matchers.add(fileSystem.getPathMatcher("glob:" + pattern));
			if (pattern.contains("/**/")) matchers.add(fileSystem.getPathMatcher("glob:" + pattern.replace("/**/", "/")));
			return List.copyOf(matchers);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid path rule: " + pattern, e);
		}
	}

	private static CompiledRule firstMatch(List<CompiledRule> rules, Path path) {
		for (CompiledRule rule : rules) if (rule.matches(path)) return rule;
		return null;
	}

	private record CompiledRule(String raw, List<PathMatcher> matchers) {
		private boolean matches(Path path) {
			return matchers.stream().anyMatch(matcher -> matcher.matches(path));
		}
	}

	public record Decision(boolean matched, String decisiveRule) {
		private static final Decision UNMATCHED = new Decision(false, null);
	}
}
