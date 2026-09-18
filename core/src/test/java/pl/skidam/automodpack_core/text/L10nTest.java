package pl.skidam.automodpack_core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class L10nTest {

	@Test
	void resolvesExactLanguage() {
		assertEquals("shared xx_yy", L10n.get("xx_yy", "test.shared"));
	}

	@Test
	void fallsBackToBaseLanguageBeforeUnderscore() {
		// xx_yy defines test.shared, so xx_zz falls back to the xx base only for keys the exact language lacks.
		assertEquals("shared english", L10n.get("xx_zz", "test.shared"));
		assertEquals("only english", L10n.get("xx_yy", "test.onlyEnglish"));
	}

	@Test
	void fallsBackToEnglishThenToTheKeyItself() {
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
}
