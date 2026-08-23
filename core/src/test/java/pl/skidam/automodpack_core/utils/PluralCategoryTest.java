package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class PluralCategoryTest {
	private static void assertCategory(String language, long count, PluralCategory expected) {
		assertEquals(expected, PluralCategory.select(language, count), language + " count " + count);
	}

	@Test
	void englishFamilyUsesOneOther() {
		for (String language : List.of("en", "de", "es", "hu", "xx", "")) {
			assertCategory(language, 0, PluralCategory.OTHER);
			assertCategory(language, 1, PluralCategory.ONE);
			assertCategory(language, 2, PluralCategory.OTHER);
			assertCategory(language, 21, PluralCategory.OTHER);
		}
	}

	@Test
	void polishTakesFewForTwoToFourButNotTwelveToFourteen() {
		assertCategory("pl", 1, PluralCategory.ONE);
		assertCategory("pl", 2, PluralCategory.FEW);
		assertCategory("pl", 4, PluralCategory.FEW);
		assertCategory("pl", 5, PluralCategory.MANY);
		assertCategory("pl", 11, PluralCategory.MANY);
		assertCategory("pl", 12, PluralCategory.MANY);
		assertCategory("pl", 13, PluralCategory.MANY);
		assertCategory("pl", 14, PluralCategory.MANY);
		assertCategory("pl", 21, PluralCategory.MANY);
		assertCategory("pl", 22, PluralCategory.FEW);
		assertCategory("pl", 112, PluralCategory.MANY);
		assertCategory("pl", 23423412, PluralCategory.MANY);
		assertCategory("pl", 23423413, PluralCategory.MANY);
		assertCategory("pl", 23423414, PluralCategory.MANY);
		assertCategory("pl", 23423422, PluralCategory.FEW);
		assertCategory("szl", 3, PluralCategory.FEW);
		assertCategory("szl", 5, PluralCategory.MANY);
	}

	@Test
	void russianAndUkrainianTakeOneForTwentyOne() {
		assertCategory("ru", 1, PluralCategory.ONE);
		assertCategory("ru", 2, PluralCategory.FEW);
		assertCategory("ru", 5, PluralCategory.MANY);
		assertCategory("ru", 11, PluralCategory.MANY);
		assertCategory("ru", 21, PluralCategory.ONE);
		assertCategory("ru", 22, PluralCategory.FEW);
		assertCategory("ru", 111, PluralCategory.MANY);
		assertCategory("ru", 121, PluralCategory.ONE);
		assertCategory("ru", 23423411, PluralCategory.MANY);
		assertCategory("ru", 23423421, PluralCategory.ONE);
		assertCategory("uk", 2, PluralCategory.FEW);
		assertCategory("uk", 12, PluralCategory.MANY);
		assertCategory("uk", 21, PluralCategory.ONE);
	}

	@Test
	void frenchPortugueseChineseAndRegionCodesFollowTheirRules() {
		assertCategory("fr", 0, PluralCategory.ONE);
		assertCategory("fr", 2, PluralCategory.OTHER);
		assertCategory("pt", 0, PluralCategory.ONE);
		assertCategory("zh", 1, PluralCategory.OTHER);
		assertCategory("zh", 5, PluralCategory.OTHER);
		assertCategory("ko", 3, PluralCategory.OTHER);
		assertCategory("pl_PL", 3, PluralCategory.FEW);
		assertCategory("pt-BR", 0, PluralCategory.ONE);
		assertCategory("zh_CN", 2, PluralCategory.OTHER);
	}
}
