package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class CanonicalHashValidationTest {
	@Test
	void singlePassCanonicalValidationMatchesEveryBmpCharacterAtCriticalOffsets() {
		char[] candidate = "a".repeat(40).toCharArray();
		for (int position : new int[]{0, 20, 39}) {
			for (int character = Character.MIN_VALUE; character <= Character.MAX_VALUE; character++) {
				candidate[position] = (char) character;
				String value = new String(candidate);
				boolean expected = HashUtils.isSha1(value) && value.equals(value.toLowerCase(Locale.ROOT));
				assertEquals(expected, HashUtils.isCanonicalSha1(value));
			}
			candidate[position] = 'a';
		}
		assertFalse(HashUtils.isCanonicalSha1(null));
		assertFalse(HashUtils.isCanonicalSha1("a".repeat(39)));
		assertFalse(HashUtils.isCanonicalSha1("a".repeat(41)));
	}

	@Test
	void requiredDigestsKeepNormalizationAndFailureSemantics() {
		assertEquals("abcdef0123".repeat(4), HashUtils.requireDigest("ABCDEF0123".repeat(4), "test"));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> HashUtils.requireDigest("invalid", "test"));
		assertEquals("Invalid test", failure.getMessage());
	}
}
