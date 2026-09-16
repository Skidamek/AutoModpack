package pl.skidam.automodpack_core.modpack.candidate;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;

public final class PathRuleSet {
	private final FileSystem fileSystem;
	private final List<CompiledRule> includes;
	private final List<CompiledRule> excludes;

	public PathRuleSet(Collection<String> rules) {
		this(rules, FileSystems.getDefault());
	}

	PathRuleSet(Collection<String> rules, FileSystem fileSystem) {
		this.fileSystem = fileSystem;
		List<CompiledRule> includes = new ArrayList<>();
		List<CompiledRule> excludes = new ArrayList<>();
		if (rules != null) for (String raw : new TreeSet<>(rules)) {
			if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Path rule is null or blank");
			boolean excluded = raw.startsWith("!");
			String pattern = excluded ? raw.substring(1) : raw;
			while (pattern.startsWith("/")) pattern = pattern.substring(1);
			while (pattern.contains("**/**")) pattern = pattern.replace("**/**", "**");
			if (pattern.isBlank()) throw new IllegalArgumentException("Path rule is empty: " + raw);
			CompiledRule compiled = new CompiledRule(raw, compile(pattern));
			(excluded ? excludes : includes).add(compiled);
		}
		this.includes = List.copyOf(includes);
		this.excludes = List.copyOf(excludes);
	}

	public Decision evaluate(String path) {
		String logicalPath = LogicalPath.normalize(path);
		Path value = fileSystem.getPath(logicalPath);
		CompiledRule include = firstMatch(includes, value);
		if (include == null) return Decision.unmatched();
		CompiledRule exclude = firstMatch(excludes, value);
		return exclude == null ? new Decision(true, true, include.raw()) : new Decision(true, false, exclude.raw());
	}

	public boolean isEmpty() {
		return includes.isEmpty();
	}

	/** Returns the narrowest filesystem prefixes that can contain an included path. */
	public Set<String> safeScanRoots() {
		if (includes.isEmpty()) return Set.of();
		Set<String> roots = new TreeSet<>();
		for (CompiledRule rule : includes) {
			String pattern = rule.raw();
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

	public record Decision(boolean matched, boolean included, String decisiveRule) {
		private static Decision unmatched() {
			return new Decision(false, false, null);
		}
	}
}
