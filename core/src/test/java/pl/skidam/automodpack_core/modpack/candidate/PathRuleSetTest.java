package pl.skidam.automodpack_core.modpack.candidate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PathRuleSetTest {
	@Test
	void aPathMatchesWhenAPositiveRuleMatchesAndNoNegatedRuleVetoesIt() {
		PathRuleSet rules = new PathRuleSet(List.of("config/**", "!config/fancymenu/**"));

		assertTrue(rules.evaluate("config/emoji.json").matched());
		assertEquals("config/**", rules.evaluate("config/emoji.json").decisiveRule());
		assertFalse(rules.evaluate("config/fancymenu/theme.txt").matched());
		assertEquals("!config/fancymenu/**", rules.evaluate("config/fancymenu/theme.txt").decisiveRule());
		assertFalse(rules.evaluate("mods/test.jar").matched());
	}

	@Test
	void negationVetoesEveryPositiveMatch() {
		PathRuleSet rules = new PathRuleSet(List.of("config/**", "assets/**", "!config/secret.txt"));

		assertTrue(rules.evaluate("assets/pack.zip").matched());
		assertFalse(rules.evaluate("config/secret.txt").matched());
		assertFalse(rules.evaluate("mods/test.jar").matched());
	}

	@Test
	void windowsSeparatorsAreTheSameRuleAsForwardSlashes() {
		PathRuleSet rules = new PathRuleSet(List.of("mods\\*.jar", "!mods\\client\\**"));

		assertTrue(rules.evaluate("mods/sodium.jar").matched());
		assertFalse(rules.evaluate("mods/client/iris.jar").matched());
		assertEquals(Set.of("mods"), rules.safeScanRoots());
	}
}
