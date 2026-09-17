package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MiniTomlTest {

	@Test
	void parsesRealisticNeoForgeModsToml() throws Exception {
		String toml = """
				modLoader = "javafml"
				loaderVersion = "[4,)"
				license = "MIT"

				# the mod itself
				[[mods]]
				modId = "somemod"
				version = "1.2.3"
				provides = ["somemod_compat"]

				[[dependencies.somemod]]
				modId = "minecraft"
				side = "BOTH"

				[[mixins]]
				config = "somemod.mixins.json"
				""";
		Map<String, Object> root = MiniToml.parse(new StringReader(toml));
		assertEquals(List.of("modLoader", "loaderVersion", "license", "mods", "dependencies", "mixins"), List.copyOf(root.keySet()));
		List<Map<String, Object>> mods = MiniToml.getTables(root, "mods");
		assertEquals(1, mods.size());
		Map<String, Object> mod = mods.get(0);
		assertEquals("somemod", MiniToml.getString(mod, "modId"));
		assertEquals("1.2.3", MiniToml.getString(mod, "version"));
		assertEquals(List.of("somemod_compat"), MiniToml.getList(mod, "provides"));
		// [[dependencies.<modId>]] lands at dependencies -> somemod, keyed by the mod's own id - the table the production lookup reads
		assertNotNull(MiniToml.getTable(root, "dependencies"));
		Map<String, Object> dependency = MiniToml.getTables(MiniToml.getTable(root, "dependencies"), "somemod").get(0);
		assertEquals("minecraft", MiniToml.getString(dependency, "modId"));
		assertEquals("BOTH", MiniToml.getString(dependency, "side"));
	}

	@Test
	void parsesMultilineArraysWithTrailingComma() throws Exception {
		String toml = """
				provides = [
					"first",
					"second",
					"third",
				]
				nested = [
					[1, 2],
					[3],
				]
				""";
		Map<String, Object> root = MiniToml.parse(new StringReader(toml));
		assertEquals(List.of("first", "second", "third"), MiniToml.getList(root, "provides"));
		assertEquals(List.of(List.of(1L, 2L), List.of(3L)), MiniToml.getList(root, "nested"));
	}

	@Test
	void parsesStringFlavors() throws Exception {
		String toml = """
				escaped = "a\\tb\\\"c\\u0041"
				literal = '${file.jarVersion}'
				multiline = \"\"\"
				hello
				world\"\"\"
				""";
		Map<String, Object> root = MiniToml.parse(new StringReader(toml));
		assertEquals("a\tb\"cA", MiniToml.getString(root, "escaped"));
		assertEquals("${file.jarVersion}", MiniToml.getString(root, "literal"));
		assertEquals("hello\nworld", MiniToml.getString(root, "multiline"));
		// CRLF input: the newline right after the opening delimiter is trimmed, CRLFs inside are kept as-is
		Map<String, Object> crlf = MiniToml.parse(new StringReader("crlf = \"\"\"\r\nfirst\r\nsecond\"\"\""));
		assertEquals("first\r\nsecond", MiniToml.getString(crlf, "crlf"));
		// A line-ending backslash trims the newline and all whitespace up to the next content
		Map<String, Object> joined = MiniToml.parse(new StringReader("joined = \"\"\"a\\   \n\tb\"\"\""));
		assertEquals("ab", MiniToml.getString(joined, "joined"));
	}

	@Test
	void parsesScalars() throws Exception {
		String toml = """
				under = 1_000
				hex = 0xFF
				oct = 0o17
				bin = 0b101
				float = 1.5e3
				negative = -42
				yes = true
				no = false
				inf = inf
				""";
		Map<String, Object> root = MiniToml.parse(new StringReader(toml));
		assertEquals(1000L, root.get("under"));
		assertEquals(255L, root.get("hex"));
		assertEquals(15L, root.get("oct"));
		assertEquals(5L, root.get("bin"));
		assertEquals(1500.0, root.get("float"));
		assertEquals(-42L, root.get("negative"));
		assertEquals(Boolean.TRUE, root.get("yes"));
		assertEquals(Boolean.FALSE, root.get("no"));
		assertEquals(Double.POSITIVE_INFINITY, root.get("inf"));
	}

	@Test
	void parsesInlineTablesAndDottedKeys() throws Exception {
		String toml = """
				server = { host = "localhost", port = 25565 }
				a.b.c = "deep"
				""";
		Map<String, Object> root = MiniToml.parse(new StringReader(toml));
		Map<String, Object> server = MiniToml.getTable(root, "server");
		assertEquals("localhost", MiniToml.getString(server, "host"));
		assertEquals(25565L, server.get("port"));
		assertEquals("deep", MiniToml.getString(MiniToml.getTable(root, "a.b"), "c"));
		// type mismatches return null, never throw
		assertNull(MiniToml.getString(root, "port"));
		assertNull(MiniToml.getList(root, "host"));
	}

	@Test
	void allowsSubTablesOnEachArrayOfTablesElement() throws Exception {
		String toml = """
				[[mods]]
				modId = "first"

				[mods.info]
				color = "red"

				[[mods]]
				modId = "second"

				[mods.info]
				color = "blue"
				""";
		Map<String, Object> root = MiniToml.parse(new StringReader(toml));
		List<Map<String, Object>> mods = MiniToml.getTables(root, "mods");
		assertEquals(2, mods.size());
		assertEquals("red", MiniToml.getString(MiniToml.getTable(mods.get(0), "info"), "color"));
		assertEquals("blue", MiniToml.getString(MiniToml.getTable(mods.get(1), "info"), "color"));
	}

	@Test
	void rejectsDuplicatesDatetimesAndKindRedefinitions() {
		assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("a = 1\na = 2")));
		assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("[a]\nx = 1\n[a]")));
		assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("date = 2024-01-01")));
		assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("time = 07:32:00")));
		assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("[[a]]\n[a]")));
		assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("a = 1\n[a]")));
		assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("a = [1, 2]\n[[a]]")));
		assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("a = []\n[[a]]")));
		assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("t = { a = 1, }")));
	}

	@Test
	void parseErrorsCarryTheLineNumber() {
		MiniToml.ParseException e = assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader("a = 1\nb = 2024-01-01")));
		assertTrue(e.getMessage().contains("line 2"), e.getMessage());
	}

	@Test
	void rejectsRedefiningDottedTablesAndStaticArrays() {
		for (String toml : List.of("a.b = 1\n[a]", "a.b.c = 1\n[a.b]", "a = [{ b = 1 }]\n[[a]]", "a = [{ b = 1 }]\n[a.child]", "a = [{ b = 1 }]\n[[a.child]]")) {
			assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader(toml)), toml);
		}
	}

	@Test
	void allowsDottedSiblingsAndImplicitHeaderParents() throws Exception {
		Map<String, Object> root = MiniToml.parse(new StringReader("a.b = 1\na.c = 2\n[a.child]\nx = 3\n[parent.child]\nx = 4\n[parent]\nx = 5"));
		assertEquals(2L, MiniToml.getTable(root, "a").get("c"));
		assertEquals(3L, MiniToml.getTable(root, "a.child").get("x"));
		assertEquals(5L, MiniToml.getTable(root, "parent").get("x"));
	}

	@Test
	void preservesHashContentAfterMultilineContinuation() throws Exception {
		for (String newline : List.of("\n", "\r\n")) {
			String toml = "value = \"\"\"start\\  " + newline + " \t" + newline + " #content\"\"\"";
			assertEquals("start#content", MiniToml.getString(MiniToml.parse(new StringReader(toml)), "value"));
			MiniToml.ParseException error = assertThrows(MiniToml.ParseException.class, () -> MiniToml.parse(new StringReader(toml + newline + "bad = nope")));
			assertTrue(error.getMessage().startsWith("line 4:"), error.getMessage());
		}
	}

	@Test
	void tableAccessorSkipsNonTablesAndKeepsOrder() throws Exception {
		Map<String, Object> root = MiniToml.parse(new StringReader("mixed = [1, { id = 'first' }, 'ignored', { id = 'second' }]\nscalar = true\nempty = []"));
		assertEquals(List.of(Map.of("id", "first"), Map.of("id", "second")), MiniToml.getTables(root, "mixed"));
		assertTrue(MiniToml.getTables(root, "missing").isEmpty());
		assertTrue(MiniToml.getTables(root, "scalar").isEmpty());
		assertTrue(MiniToml.getTables(root, "empty").isEmpty());
	}
}
