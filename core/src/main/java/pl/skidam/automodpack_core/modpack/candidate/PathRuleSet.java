package pl.skidam.automodpack_core.modpack.candidate;

import java.util.*;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;

/**
 * A set of glob path rules. A path is matched when a positive rule matches it and no '!'-negated rule does - one
 * primitive each field points at its own posture: syncedFiles includes, excludedFiles excludes (where a '!' rule
 * un-excludes), allowEditsInFiles marks editable.
 */
public final class PathRuleSet {
	private final List<CompiledRule> positive;
	private final List<CompiledRule> negated;

	/** Matching is a case-sensitive {@code /}-only glob so the default filesystem cannot fold it. */
	public PathRuleSet(Collection<String> rules) {
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
		CompiledRule match = firstMatch(positive, logicalPath);
		if (match == null) return Decision.UNMATCHED;
		CompiledRule veto = firstMatch(negated, logicalPath);
		return veto == null ? new Decision(true, match.raw()) : new Decision(false, veto.raw());
	}

	/** Whether a positive rule matches and no {@code !} rule vetoes it. Posture (include / exclude / editable) belongs to the caller. */
	public boolean matches(String path) {
		return evaluate(path).matched();
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

	private static List<Pattern> compile(String pattern) {
		try {
			List<Pattern> matchers = new ArrayList<>();
			matchers.add(globToRegex(pattern));
			if (pattern.contains("/**/")) matchers.add(globToRegex(pattern.replace("/**/", "/")));
			return List.copyOf(matchers);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid path rule: " + pattern, e);
		}
	}

	/** Unix glob, case-sensitive, {@code /} is the only separator. {@code *} stays in one segment; {@code **} may cross; {@code [!...]} negates the class. */
	private static Pattern globToRegex(String glob) {
		return Pattern.compile('^' + globToRegexBody(glob) + '$');
	}

	private static String globToRegexBody(String glob) {
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			if (c == '*') {
				if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
					regex.append(".*");
					i++;
				} else regex.append("[^/]*");
			} else if (c == '?') regex.append("[^/]");
			else if (c == '[') {
				int close = glob.indexOf(']', i + 1);
				if (close < 0) regex.append("\\[");
				else {
					regex.append('[');
					int body = i + 1;
					if (body < close && glob.charAt(body) == '!') {
						regex.append('^');
						body++;
					}
					regex.append(glob, body, close).append(']');
					i = close;
				}
			} else if (c == '{') {
				int close = glob.indexOf('}', i + 1);
				if (close < 0) regex.append("\\{");
				else {
					regex.append("(?:");
					boolean first = true;
					for (String alternative : glob.substring(i + 1, close).split(",", -1)) {
						if (!first) regex.append('|');
						first = false;
						regex.append(globToRegexBody(alternative));
					}
					regex.append(')');
					i = close;
				}
			} else {
				if (".^$+()|\\".indexOf(c) >= 0) regex.append('\\');
				regex.append(c);
			}
		}
		return regex.toString();
	}

	private static CompiledRule firstMatch(List<CompiledRule> rules, String path) {
		for (CompiledRule rule : rules) if (rule.matches(path)) return rule;
		return null;
	}

	private record CompiledRule(String raw, List<Pattern> matchers) {
		private boolean matches(String path) {
			return matchers.stream().anyMatch(matcher -> matcher.matcher(path).matches());
		}
	}

	public record Decision(boolean matched, String decisiveRule) {
		private static final Decision UNMATCHED = new Decision(false, null);
	}
}
