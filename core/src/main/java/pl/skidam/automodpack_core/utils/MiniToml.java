package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal TOML-subset reader for our one job - parsing forge/neoforge mods.toml metadata - not a general TOML implementation.
 * Values map to strings → String, integers → Long, floats → Double, booleans → Boolean, arrays → {@link List},
 * tables/inline tables → {@link LinkedHashMap} and [[array of tables]] → List of tables appended in file order.
 * Datetimes are not supported and fail loudly.
 */
public final class MiniToml {

	/** Thrown on any syntax or structure violation; the message always names the 1-based line. */
	public static final class ParseException extends RuntimeException {
		ParseException(int line, String message) {
			super("line " + line + ": " + message);
		}
	}

	public static Map<String, Object> parse(Reader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		char[] buffer = new char[8192];
		int read;
		while ((read = reader.read(buffer)) != -1) sb.append(buffer, 0, read);
		return new MiniToml(sb.toString()).parseDocument();
	}

	/** Walks the dotted key path (bare segments) over nested tables; null when any segment is missing or not a table. */
	public static Map<String, Object> getTable(Map<String, Object> table, String dottedKey) {
		Map<String, Object> current = table;
		for (String segment : dottedKey.split("\\.", -1)) {
			if (!(current.get(segment) instanceof Map)) return null;
			current = cast(current.get(segment));
		}
		return current;
	}

	/** Single key lookup; the value only when it is a list, else null on type mismatch. */
	public static List<Object> getList(Map<String, Object> table, String key) {
		return table.get(key) instanceof List ? cast(table.get(key)) : null;
	}

	public static List<Map<String, Object>> getTables(Map<String, Object> table, String key) {
		List<Object> values = getList(table, key);
		if (values == null) return List.of();
		List<Map<String, Object>> tables = new ArrayList<>();
		for (Object value : values) if (value instanceof Map) tables.add(cast(value));
		return tables;
	}

	/** Single key lookup; the value only when it is a string, else null on type mismatch. */
	public static String getString(Map<String, Object> table, String key) {
		return table.get(key) instanceof String ? (String) table.get(key) : null;
	}

	@SuppressWarnings("unchecked")
	private static <T> T cast(Object value) {
		return (T) value;
	}

	private final String src;
	private int pos;
	private int line = 1;
	/** Tables explicitly opened by a [...] header - identity-keyed (map content mutates as it fills, so content hashing would corrupt the set), so each element of an array of tables can define its own sub-tables. */
	private final Set<Map<String, Object>> definedTables = Collections.newSetFromMap(new IdentityHashMap<>());

	private MiniToml(String src) {
		this.src = src;
	}

	private Map<String, Object> parseDocument() {
		Map<String, Object> root = new LinkedHashMap<>();
		Map<String, Object> current = root;
		while (true) {
			skipWhitespaceAndComments();
			if (pos >= src.length()) return root;
			if (peek() == '[') current = parseHeader(root);
			else {
				parseKeyValue(current);
				expectEndOfLine();
			}
		}
	}

	private Map<String, Object> parseHeader(Map<String, Object> root) {
		boolean arrayOfTables = peek(1) == '[';
		pos += arrayOfTables ? 2 : 1;
		List<String> path = parseKeyPath();
		expect(']');
		if (arrayOfTables) expect(']');
		expectEndOfLine();
		Map<String, Object> parent = root;
		for (int i = 0; i < path.size() - 1; i++) parent = descend(parent, path.get(i));
		String last = path.get(path.size() - 1);
		String name = String.join(".", path);
		Object existing = parent.get(last);
		if (arrayOfTables) {
			if (existing instanceof List<?> tables) { // an array-of-tables list is never empty and only ever holds tables
				if (tables.isEmpty() || !(tables.get(0) instanceof Map)) throw error("'" + name + "' is already defined as a different kind");
				Map<String, Object> table = new LinkedHashMap<>();
				List<Object> appendable = cast(tables);
				appendable.add(table);
				return table;
			}
			if (existing != null) throw error("'" + name + "' is already defined as a different kind");
			Map<String, Object> table = new LinkedHashMap<>();
			List<Object> tables = new ArrayList<>();
			tables.add(table);
			parent.put(last, tables);
			return table;
		}
		if (existing instanceof Map) {
			Map<String, Object> table = cast(existing);
			if (!definedTables.add(table)) throw error("duplicate table '" + name + "'");
			return table;
		}
		if (existing != null) throw error("'" + name + "' is already defined as a different kind");
		Map<String, Object> table = new LinkedHashMap<>();
		parent.put(last, table);
		definedTables.add(table);
		return table;
	}

	/** Steps into (or creates) the intermediate table {@code key} of a header path; an array of tables means its newest element. */
	private Map<String, Object> descend(Map<String, Object> table, String key) {
		Object value = table.get(key);
		if (value instanceof Map) return cast(value);
		if (value instanceof List) {
			List<Object> list = cast(value);
			if (!list.isEmpty() && list.get(list.size() - 1) instanceof Map) return cast(list.get(list.size() - 1));
			throw error("'" + key + "' is not a table");
		}
		if (value == null) {
			Map<String, Object> created = new LinkedHashMap<>();
			table.put(key, created);
			return created;
		}
		throw error("'" + key + "' is not a table");
	}

	private void parseKeyValue(Map<String, Object> table) {
		List<String> path = parseKeyPath();
		skipInlineWhitespace();
		expect('=');
		skipInlineWhitespace();
		Object value = parseValue();
		Map<String, Object> target = table;
		for (int i = 0; i < path.size() - 1; i++) {
			Object existing = target.get(path.get(i));
			if (!(existing instanceof Map)) {
				if (existing != null) throw error("'" + path.get(i) + "' is not a table");
				Map<String, Object> created = new LinkedHashMap<>();
				target.put(path.get(i), created);
				existing = created;
			}
			target = cast(existing);
		}
		String last = path.get(path.size() - 1);
		if (target.containsKey(last)) throw error("duplicate key '" + last + "'");
		target.put(last, value);
	}

	private List<String> parseKeyPath() {
		List<String> path = new ArrayList<>();
		while (true) {
			skipInlineWhitespace();
			path.add(parseKeySegment());
			skipInlineWhitespace();
			if (peek() == '.') pos++;
			else return path;
		}
	}

	private String parseKeySegment() {
		char c = peek();
		if (c == '"') return parseBasicString();
		if (c == '\'') return parseLiteralString();
		int start = pos;
		while (isBareKeyChar(peek())) pos++;
		if (pos == start) throw error("expected a key");
		return src.substring(start, pos);
	}

	private static boolean isBareKeyChar(char c) {
		return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-';
	}

	private Object parseValue() {
		char c = peek();
		return switch (c) {
			case '"' -> peek(1) == '"' && peek(2) == '"' ? parseMultilineBasicString() : parseBasicString();
			case '\'' -> peek(1) == '\'' && peek(2) == '\'' ? parseMultilineLiteralString() : parseLiteralString();
			case '[' -> parseArray();
			case '{' -> parseInlineTable();
			default -> parseBareToken();
		};
	}

	private List<Object> parseArray() {
		pos++;
		List<Object> array = new ArrayList<>();
		while (true) {
			skipWhitespaceAndComments();
			if (peek() == ']') {
				pos++;
				return array;
			}
			array.add(parseValue());
			skipWhitespaceAndComments();
			if (peek() == ',') {
				pos++;
			} else if (peek() == ']') {
				pos++;
				return array;
			} else {
				throw error("expected ',' or ']' in array");
			}
		}
	}

	private Map<String, Object> parseInlineTable() {
		pos++;
		Map<String, Object> table = new LinkedHashMap<>();
		skipInlineWhitespace();
		if (peek() == '}') {
			pos++;
			return table;
		}
		while (true) {
			parseKeyValue(table);
			skipInlineWhitespace();
			if (peek() == ',') {
				pos++;
				skipInlineWhitespace();
				if (peek() == '}') throw error("trailing comma in inline table");
			} else if (peek() == '}') {
				pos++;
				return table;
			} else {
				throw error("expected ',' or '}' in inline table");
			}
		}
	}

	private Object parseBareToken() {
		int start = pos;
		while (pos < src.length()) {
			char c = src.charAt(pos);
			if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ',' || c == ']' || c == '}' || c == '#') break;
			pos++;
		}
		String token = src.substring(start, pos);
		if (token.isEmpty()) throw error("expected a value");
		switch (token) {
			case "true" :
				return Boolean.TRUE;
			case "false" :
				return Boolean.FALSE;
			case "inf", "+inf" :
				return Double.POSITIVE_INFINITY;
			case "-inf" :
				return Double.NEGATIVE_INFINITY;
			case "nan", "+nan", "-nan" :
				return Double.NaN;
		}
		boolean negative = token.startsWith("-");
		String unsigned = token.startsWith("+") || negative ? token.substring(1) : token;
		if (token.indexOf(':') >= 0 || unsigned.matches("\\d{4}-\\d{2}.*")) throw error("datetimes are not supported: '" + token + "'");
		String digits = unsigned.replace("_", "");
		try {
			if (digits.startsWith("0x") || digits.startsWith("0o") || digits.startsWith("0b")) {
				int radix = digits.charAt(1) == 'x' ? 16 : digits.charAt(1) == 'o' ? 8 : 2;
				return Long.parseLong(negative ? "-" + digits.substring(2) : digits.substring(2), radix);
			}
			if (digits.indexOf('.') >= 0 || digits.indexOf('e') >= 0 || digits.indexOf('E') >= 0) return Double.parseDouble(digits);
			return Long.parseLong(negative ? "-" + digits : digits);
		} catch (NumberFormatException e) {
			throw error("invalid value '" + token + "'");
		}
	}

	private String parseBasicString() {
		pos++;
		StringBuilder sb = new StringBuilder();
		while (true) {
			if (pos >= src.length() || peek() == '\n') throw error("unterminated string");
			char c = src.charAt(pos++);
			if (c == '"') return sb.toString();
			if (c == '\\') parseEscape(sb);
			else sb.append(c);
		}
	}

	private String parseLiteralString() {
		pos++;
		int start = pos;
		while (true) {
			if (pos >= src.length() || peek() == '\n') throw error("unterminated string");
			char c = src.charAt(pos++);
			if (c == '\'') return src.substring(start, pos - 1);
		}
	}

	private String parseMultilineBasicString() {
		pos += 3;
		trimFirstNewline();
		StringBuilder sb = new StringBuilder();
		while (true) {
			if (pos >= src.length()) throw error("unterminated multi-line string");
			char c = src.charAt(pos++);
			if (c == '"') {
				int run = 1;
				while (pos + run - 1 < src.length() && src.charAt(pos + run - 1) == '"') run++;
				if (run >= 3) {
					for (int i = 0; i < run - 3; i++) sb.append('"');
					pos += run - 1;
					return sb.toString();
				}
				for (int i = 0; i < run; i++) sb.append('"');
				pos += run - 1;
			} else if (c == '\\') {
				// A line-ending backslash trims the whitespace and newlines up to the next non-whitespace character
				int scan = pos;
				while (scan < src.length() && (src.charAt(scan) == ' ' || src.charAt(scan) == '\t' || src.charAt(scan) == '\r')) scan++;
				if (scan < src.length() && src.charAt(scan) == '\n') {
					pos = scan;
					skipWhitespaceAndComments();
				} else {
					parseEscape(sb);
				}
			} else {
				sb.append(c);
				if (c == '\n') line++;
			}
		}
	}

	private String parseMultilineLiteralString() {
		pos += 3;
		trimFirstNewline();
		StringBuilder sb = new StringBuilder();
		while (true) {
			if (pos >= src.length()) throw error("unterminated multi-line string");
			char c = src.charAt(pos++);
			if (c == '\'') {
				int run = 1;
				while (pos + run - 1 < src.length() && src.charAt(pos + run - 1) == '\'') run++;
				if (run >= 3) {
					for (int i = 0; i < run - 3; i++) sb.append('\'');
					pos += run - 1;
					return sb.toString();
				}
				for (int i = 0; i < run; i++) sb.append('\'');
				pos += run - 1;
			} else {
				sb.append(c);
				if (c == '\n') line++;
			}
		}
	}

	private void parseEscape(StringBuilder sb) {
		if (pos >= src.length()) throw error("unterminated string");
		char c = src.charAt(pos++);
		switch (c) {
			case 'b' -> sb.append('\b');
			case 't' -> sb.append('\t');
			case 'n' -> sb.append('\n');
			case 'f' -> sb.append('\f');
			case 'r' -> sb.append('\r');
			case '"' -> sb.append('"');
			case '\\' -> sb.append('\\');
			case 'u' -> appendCodePoint(sb, 4);
			case 'U' -> appendCodePoint(sb, 8);
			default -> throw error("invalid escape '\\" + c + "'");
		}
	}

	private void appendCodePoint(StringBuilder sb, int digitCount) {
		if (pos + digitCount > src.length()) throw error("invalid unicode escape");
		int value = 0;
		for (int i = 0; i < digitCount; i++) {
			int digit = Character.digit(src.charAt(pos++), 16);
			if (digit < 0) throw error("invalid unicode escape");
			value = value << 4 | digit;
		}
		if (value > 0x10FFFF || value >= 0xD800 && value <= 0xDFFF) throw error("invalid unicode code point");
		sb.appendCodePoint(value);
	}

	private void trimFirstNewline() {
		if (peek() == '\r' && peek(1) == '\n') {
			pos += 2;
			line++;
		} else if (peek() == '\n') {
			pos++;
			line++;
		}
	}

	private void skipWhitespaceAndComments() {
		while (pos < src.length()) {
			char c = src.charAt(pos);
			if (c == '\n') {
				line++;
				pos++;
			} else if (c == ' ' || c == '\t' || c == '\r') {
				pos++;
			} else if (c == '#') {
				while (pos < src.length() && src.charAt(pos) != '\n') pos++;
			} else {
				return;
			}
		}
	}

	private void skipInlineWhitespace() {
		while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) pos++;
	}

	private void expectEndOfLine() {
		skipInlineWhitespace();
		if (pos < src.length() && src.charAt(pos) == '#') while (pos < src.length() && src.charAt(pos) != '\n') pos++;
		if (pos >= src.length()) return;
		char c = src.charAt(pos);
		if (c == '\n') {
			line++;
			pos++;
		} else if (c != '\r') {
			throw error("expected end of line");
		}
	}

	private void expect(char expected) {
		if (peek() != expected) throw error("expected '" + expected + "'");
		pos++;
	}

	private char peek() {
		return pos < src.length() ? src.charAt(pos) : '\0';
	}

	private char peek(int offset) {
		return pos + offset < src.length() ? src.charAt(pos + offset) : '\0';
	}

	private ParseException error(String message) {
		return new ParseException(line, message);
	}
}
