package pl.skidam.automodpack_core.modpack.candidate;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;

/**
 * A set of glob path rules. A path is matched when a positive rule matches it and no '!'-negated rule does - one
 * primitive each field points at its own posture: syncedFiles includes, excludedFiles excludes (where a '!' rule
 * un-excludes), allowEditsInFiles marks editable.
 */
public final class PathRuleSet {
	private final FileSystem fileSystem;
	private final List<CompiledRule> positive;
	private final List<CompiledRule> negated;

	public PathRuleSet(Collection<String> rules) {
		this(rules, FileSystems.getDefault());
	}

	PathRuleSet(Collection<String> rules, FileSystem fileSystem) {
		this.fileSystem = fileSystem;
		List<CompiledRule> positive = new ArrayList<>();
		List<CompiledRule> negated = new ArrayList<>();
		if (rules != null) for (String raw : new TreeSet<>(rules)) {
			if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Path rule is null or blank");
			boolean negation = raw.startsWith("!");
			String pattern = negation ? raw.substring(1) : raw;
			pattern = pattern.replace('\\', '/');
			while (pattern.startsWith("/")) pattern = pattern.substring(1);
			while (pattern.contains("**/**")) pattern = pattern.replace("**/**", "**");
			if (pattern.isBlank()) throw new IllegalArgumentException("Path rule is empty: " + raw);
			(negation ? negated : positive).add(new CompiledRule(raw, compile(pattern)));
		}
		this.positive = List.copyOf(positive);
		this.negated = List.copyOf(negated);
	}

	public Decision evaluate(String path) {
		String logicalPath = LogicalPath.normalize(path);
		Path value = fileSystem.getPath(logicalPath);
		CompiledRule match = firstMatch(positive, value);
		if (match == null) return Decision.UNMATCHED;
		CompiledRule veto = firstMatch(negated, value);
		return veto == null ? new Decision(true, match.raw()) : new Decision(false, veto.raw());
	}

	public boolean isEmpty() {
		return positive.isEmpty();
	}

	/** Returns the narrowest filesystem prefixes that can contain a matched path. */
	public Set<String> safeScanRoots() {
		if (positive.isEmpty()) return Set.of();
		Set<String> roots = new TreeSet<>();
		for (CompiledRule rule : positive) {
			String pattern = rule.raw().replace('\\', '/');
			while (pattern.startsWith("!")) pattern = pattern.substring(1);
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
