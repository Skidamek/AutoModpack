package pl.skidam.automodpack_core.config;

import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Read-only parser for the legacy v5-rc {@code .conf} HOCON-subset format (matching pre-rewrite
 * {@code reconf} {@code Syntax}/{@code Parser} rules), used exclusively for one-shot migration of
 * {@code server.conf} and {@code client.conf} to {@code .toml}.
 */
final class LegacyConfParser {
	private static final int MAX_DEPTH = 255;
	private static final boolean[] START_EXCLUDED = asciiSet('\t', '\n', '\r', ' ', '{', '}', '[', ']', ',', '"', '#', '$', '+', '=', '\\', '@', '^', '&', '`', ':');
	private static final boolean[] VALUE_EXCLUDED = asciiSet('\t', '\n', '\r', ' ', '{', '}', '[', ']', ',', '"', '#', '$', '\\', '@', '^', '&', '`');

	private final String s;
	private final int len;
	private int pos;
	private int depth;

	private LegacyConfParser(String s) {
		this.s = s;
		this.len = s.length();
	}

	static JsonObject parse(byte[] bytes, String fileName) {
		String text = new String(bytes, StandardCharsets.UTF_8);
		if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
		return new LegacyConfParser(text).parseRoot(fileName);
	}

	private JsonObject parseRoot(String fileName) {
		skipTrivia();
		if (pos >= len) return new JsonObject();
		if (s.charAt(pos) == '{') {
			JsonObject root = parseObject(fileName);
			skipTrivia();
			if (pos < len) throw error(fileName, "unexpected trailing content");
			return root;
		}
		if (s.charAt(pos) == '[') {
			throw error(fileName, "root must be an object, not an array");
		}
		return parseMembers(fileName, '\0');
	}

	private JsonObject parseObject(String fileName) {
		if (++depth > MAX_DEPTH) throw error(fileName, "nesting exceeds " + MAX_DEPTH);
		pos++; // '{'
		JsonObject obj = parseMembers(fileName, '}');
		if (pos >= len || s.charAt(pos) != '}') throw error(fileName, "unterminated object");
		pos++; // '}'
		depth--;
		return obj;
	}

	private JsonObject parseMembers(String fileName, char closer) {
		JsonObject obj = new JsonObject();
		boolean first = true;
		boolean afterComma = false;
		while (true) {
			boolean sawNewline = skipTrivia();
			if (pos >= len) {
				if (closer == '\0') return obj;
				throw error(fileName, "unterminated object");
			}
			char c = s.charAt(pos);
			if (closer != '\0' && c == closer) return obj;
			if (c == ',') {
				if (first || afterComma) throw error(fileName, "unexpected ','");
				pos++;
				afterComma = true;
				continue;
			}
			if (!first && !afterComma && !sawNewline) {
				throw error(fileName, "expected newline or ',' between entries");
			}
			String key = parseKey(fileName);
			skipTrivia();
			if (pos >= len) throw error(fileName, "expected ':' or '{' after key '" + key + "'");
			char sep = s.charAt(pos);
			JsonElement value;
			if (sep == ':') {
				pos++;
				skipTrivia();
				value = parseValue(fileName);
			} else if (sep == '{') {
				value = parseObject(fileName);
			} else {
				throw error(fileName, "expected ':' or '{' after key '" + key + "'");
			}
			if (obj.has(key)) throw error(fileName, "duplicate key '" + key + "'");
			obj.add(key, value);
			first = false;
			afterComma = false;
		}
	}

	private JsonArray parseArray(String fileName) {
		if (++depth > MAX_DEPTH) throw error(fileName, "nesting exceeds " + MAX_DEPTH);
		pos++; // '['
		JsonArray arr = new JsonArray();
		boolean first = true;
		boolean afterComma = false;
		while (true) {
			boolean sawNewline = skipTrivia();
			if (pos >= len) throw error(fileName, "unterminated array");
			char c = s.charAt(pos);
			if (c == ']') {
				pos++;
				depth--;
				return arr;
			}
			if (c == ',') {
				if (first || afterComma) throw error(fileName, "unexpected ','");
				pos++;
				afterComma = true;
				continue;
			}
			if (!first && !afterComma && !sawNewline) {
				throw error(fileName, "expected newline or ',' between array elements");
			}
			arr.add(parseValue(fileName));
			first = false;
			afterComma = false;
		}
	}

	private JsonElement parseValue(String fileName) {
		if (pos >= len) throw error(fileName, "expected value");
		char c = s.charAt(pos);
		if (c == '{') return parseObject(fileName);
		if (c == '[') return parseArray(fileName);
		if (c == '"') {
			if (pos + 2 < len && s.charAt(pos + 1) == '"' && s.charAt(pos + 2) == '"') {
				return new JsonPrimitive(parseTripleQuotedString(fileName));
			}
			return new JsonPrimitive(parseQuotedString(fileName));
		}
		String bare = parseBareValue(fileName);
		if (bare.equals("true")) return new JsonPrimitive(true);
		if (bare.equals("false")) return new JsonPrimitive(false);
		if (bare.equals("null")) return JsonNull.INSTANCE;
		if (isNumberLiteral(bare)) return JsonParser.parseString(bare);
		return new JsonPrimitive(bare);
	}

	private String parseKey(String fileName) {
		if (s.charAt(pos) == '"') return parseQuotedString(fileName);
		int start = pos;
		while (pos < len) {
			int cp = s.codePointAt(pos);
			if (!canStartBare(cp)) break;
			pos += Character.charCount(cp);
		}
		if (pos == start) throw error(fileName, "expected key");
		return s.substring(start, pos);
	}

	private String parseBareValue(String fileName) {
		int start = pos;
		if (!canStartBare(s.codePointAt(pos))) throw error(fileName, "unexpected token '" + s.charAt(pos) + "'");
		while (pos < len) {
			int cp = s.codePointAt(pos);
			if (!canContinueValue(cp)) break;
			pos += Character.charCount(cp);
		}
		return s.substring(start, pos);
	}

	private String parseQuotedString(String fileName) {
		pos++; // opening '"'
		StringBuilder out = new StringBuilder();
		while (pos < len) {
			char c = s.charAt(pos++);
			if (c == '"') return out.toString();
			if (c == '\n' || c == '\r') throw error(fileName, "unterminated string");
			if (c != '\\') {
				out.append(c);
				continue;
			}
			if (pos >= len) throw error(fileName, "unterminated string escape");
			char e = s.charAt(pos++);
			switch (e) {
				case '"' -> out.append('"');
				case '\\' -> out.append('\\');
				case '/' -> out.append('/');
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 't' -> out.append('\t');
				case 'u' -> {
					if (pos + 4 > len) throw error(fileName, "incomplete \\u escape");
					try {
						out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
					} catch (NumberFormatException ex) {
						throw error(fileName, "invalid \\u escape");
					}
					pos += 4;
				}
				default -> throw error(fileName, "unknown escape '\\" + e + "'");
			}
		}
		throw error(fileName, "unterminated string");
	}

	private String parseTripleQuotedString(String fileName) {
		pos += 3;
		int contentStart = pos;
		while (pos < len) {
			if (s.charAt(pos) == '"') {
				int run = 0;
				while (pos + run < len && s.charAt(pos + run) == '"') run++;
				if (run >= 3) {
					String content = s.substring(contentStart, pos) + "\"".repeat(run - 3);
					pos += run;
					return content;
				}
				pos += run;
			} else {
				pos++;
			}
		}
		throw error(fileName, "unterminated triple-quoted string");
	}

	private boolean skipTrivia() {
		boolean sawNewline = false;
		while (pos < len) {
			char c = s.charAt(pos);
			if (c == ' ' || c == '\t') {
				pos++;
			} else if (c == '\n' || c == '\r') {
				sawNewline = true;
				pos++;
			} else if (c == '#') {
				while (pos < len && s.charAt(pos) != '\n' && s.charAt(pos) != '\r') pos++;
			} else {
				break;
			}
		}
		return sawNewline;
	}

	private ConfigTools.ConfigParseException error(String fileName, String message) {
		int line = 1;
		int col = 1;
		for (int i = 0; i < pos && i < len; i++) {
			if (s.charAt(i) == '\n') {
				line++;
				col = 1;
			} else {
				col++;
			}
		}
		return new ConfigTools.ConfigParseException("Invalid legacy configuration " + fileName + " at line " + line + ":" + col + ": " + message);
	}

	private static boolean forbiddenOutsideStrings(int cp) {
		if (cp < 0x20 && cp != '\t' && cp != '\n' && cp != '\r') return true;
		return cp == 0x7F || cp == 0x2028 || cp == 0x2029 || (cp >= 0x80 && cp <= 0x9F);
	}

	private static boolean canStartBare(int cp) {
		if (forbiddenOutsideStrings(cp) || cp == 0xFEFF) return false;
		return cp >= 128 || !START_EXCLUDED[cp];
	}

	private static boolean canContinueValue(int cp) {
		if (forbiddenOutsideStrings(cp) || cp == 0xFEFF) return false;
		return cp >= 128 || !VALUE_EXCLUDED[cp];
	}

	private static boolean isNumberLiteral(String s) {
		int i = 0;
		int n = s.length();
		if (i < n && s.charAt(i) == '-') i++;
		if (i >= n) return false;
		char c = s.charAt(i);
		if (c == '0') i++;
		else if (c >= '1' && c <= '9') while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
		else return false;
		if (i < n && s.charAt(i) == '.') {
			i++;
			if (i >= n || s.charAt(i) < '0' || s.charAt(i) > '9') return false;
			while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
		}
		if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
			i++;
			if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
			if (i >= n || s.charAt(i) < '0' || s.charAt(i) > '9') return false;
			while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
		}
		return i == n;
	}

	private static boolean[] asciiSet(char... chars) {
		boolean[] table = new boolean[128];
		for (char c : chars) table[c] = true;
		return table;
	}
}
