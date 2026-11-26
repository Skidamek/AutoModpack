package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class LegacyConfParserTest {

	@Test
	void parsesBareUrlsGlobsTripleQuotesBomAndColonlessObjects() {
		String conf = "\uFEFF# header comment\r\n"
				+ "nag-clickable-link: https://modrinth.com/project/automodpack\r\n"
				+ "note: \"\"\"multi\nline \"quoted\"\"\"\"\"\" # trailing\r\n"
				+ "modpack {\r\n"
				+ "  name: \"My Pack\"\r\n"
				+ "  General {\r\n"
				+ "    main {\r\n"
				+ "      from-server: [mods/*.jar, !kubejs/server_scripts/**, \"**/*.{tmp,disabled,bak}\"]\r\n"
				+ "      bind-port: -1\r\n"
				+ "      enabled: true\r\n"
				+ "    }\r\n"
				+ "  }\r\n"
				+ "}\r\n";

		JsonObject root = LegacyConfParser.parse(conf.getBytes(StandardCharsets.UTF_8), "server.conf");
		assertEquals("https://modrinth.com/project/automodpack", root.get("nag-clickable-link").getAsString());
		assertEquals("multi\nline \"quoted\"\"\"", root.get("note").getAsString());
		JsonObject main = root.getAsJsonObject("modpack").getAsJsonObject("General").getAsJsonObject("main");
		assertEquals("mods/*.jar", main.getAsJsonArray("from-server").get(0).getAsString());
		assertEquals("!kubejs/server_scripts/**", main.getAsJsonArray("from-server").get(1).getAsString());
		assertEquals("**/*.{tmp,disabled,bak}", main.getAsJsonArray("from-server").get(2).getAsString());
		assertEquals(-1, main.get("bind-port").getAsInt());
		assertTrue(main.get("enabled").getAsBoolean());
	}

	@Test
	void rejectsDuplicateKeysAndAdjacentEntries() {
		assertThrows(ConfigTools.ConfigParseException.class,
				() -> LegacyConfParser.parse("a: 1\na: 2\n".getBytes(StandardCharsets.UTF_8), "server.conf"));
		assertThrows(ConfigTools.ConfigParseException.class,
				() -> LegacyConfParser.parse("a: 1 b: 2\n".getBytes(StandardCharsets.UTF_8), "server.conf"));
	}
}
