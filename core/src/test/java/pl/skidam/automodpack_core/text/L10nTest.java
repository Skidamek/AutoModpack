package pl.skidam.automodpack_core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class L10nTest {

	@Test
	void resolvesExactLanguage() {
		assertEquals("shared xx_yy", L10n.get("xx_yy", "test.shared"));
	}

	@Test
	void unsupportedRegionUsesTheBundledVariantOfItsLanguage() {
		// The bundled files are the only language list: a region this jar does not translate reads its language's file.
		assertEquals("shared xx_yy", L10n.get("xx_zz", "test.shared"));
		assertEquals("shared de_de", L10n.get("de_at", "test.shared"));
		assertEquals("shared es_es", L10n.get("es_uy", "test.shared"));
		assertEquals("shared pl_pl", L10n.get("pl", "test.shared"));
	}

	@Test
	void regionalEnglishFallsBackToUsEnglishInsteadOfNoveltyVariants() {
		assertEquals("shared english", L10n.get("en_gb", "test.shared"));
		assertEquals("shared english", L10n.get("en_au", "test.shared"));
		assertEquals("shared english", L10n.get("en", "test.shared"));
		assertEquals("shared pirate", L10n.get("en_pt", "test.shared"));
	}

	@Test
	void chineseRegionsDistinguishTraditionalAndSimplifiedFiles() {
		assertEquals("shared zh_tw", L10n.get("zh_tw", "test.shared"));
		assertEquals("shared zh_tw", L10n.get("zh_hk", "test.shared"));
		assertEquals("shared zh_tw", L10n.get("zh_mo", "test.shared"));
		assertEquals("shared zh_tw", L10n.get("zh_hant", "test.shared"));
		assertEquals("shared zh_cn", L10n.get("zh_cn", "test.shared"));
		assertEquals("shared zh_cn", L10n.get("zh_sg", "test.shared"));
		assertEquals("shared zh_cn", L10n.get("zh_hans", "test.shared"));
		assertEquals("shared zh_cn", L10n.get("zh", "test.shared"));
	}

	@Test
	void missingKeyFallsBackToEnglish() {
		assertEquals("only english", L10n.get("xx_yy", "test.onlyEnglish"));
		assertEquals("only english", L10n.get("xx_zz", "test.onlyEnglish"));
		assertEquals("test.neverTranslated", L10n.get("xx_zz", "test.neverTranslated"));
	}

	@Test
	void unknownLanguageFallsBackToEnglish() {
		assertEquals("only english", L10n.get("zz_zz", "test.onlyEnglish"));
	}

	@Test
	void formatsArgsOnlyWhenPresent() {
		assertEquals("value is %s and %s", L10n.get("en_us", "test.formatted"));
		assertEquals("value is 7 and ok", L10n.get("en_us", "test.formatted", 7, "ok"));
	}

	@Test
	void mapsJavaLocalesToMinecraftLanguageCodes() {
		assertEquals("en_us", L10n.languageFor(null));
		assertEquals("en_us", L10n.languageFor(Locale.ROOT));
		assertEquals("pl", L10n.languageFor(new Locale("pl")));
		assertEquals("en_us", L10n.languageFor(Locale.US));
		assertEquals("zh_cn", L10n.languageFor(Locale.SIMPLIFIED_CHINESE));
		assertEquals("zh_tw", L10n.languageFor(Locale.TRADITIONAL_CHINESE));
		assertEquals("zh_hk", L10n.languageFor(new Locale("zh", "HK")));
		assertEquals("zh_mo", L10n.languageFor(new Locale("zh", "MO")));
		assertEquals("zh_hant", L10n.languageFor(Locale.forLanguageTag("zh-Hant")));
		assertEquals("zh", L10n.languageFor(Locale.forLanguageTag("zh-Hans")));
	}

	@Test
	void readsPreloadLanguageFromOptionsTxtAndFallsBackToSystemLocale(@TempDir Path gameDir) throws Exception {
		Path options = gameDir.resolve("options.txt");
		assertEquals(L10n.languageFor(Locale.getDefault()), L10n.languageFromOptions(options));

		Files.writeString(options, "fov:0.0\nlang:  PL_pl \nguiScale:2\n", StandardCharsets.UTF_8);
		assertEquals("pl_pl", L10n.languageFromOptions(options));

		Files.writeString(options, "fov:0.0\nlang:   \n", StandardCharsets.UTF_8);
		assertEquals(L10n.languageFor(Locale.getDefault()), L10n.languageFromOptions(options));
	}

	@Test
	void matchesPhraseNormalizesCaseSpacesAndFullwidthCharacters() {
		assertTrue(L10n.matchesPhrase("I accept the risk", "I accept the risk"));
		assertTrue(L10n.matchesPhrase("  i   ACCEPT\t the\nRISK ", "I accept the risk"));
		assertTrue(L10n.matchesPhrase("\uFF29\u3000accept\u00A0the\u3000risk", "I accept the risk"));
		assertFalse(L10n.matchesPhrase("I do not accept the risk", "I accept the risk"));
		assertFalse(L10n.matchesPhrase(null, "I accept the risk"));
		assertFalse(L10n.matchesPhrase("I accept the risk", null));
	}
}
