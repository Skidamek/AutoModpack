package pl.skidam.automodpack_core.modpack.candidate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PathRuleSetTest {
	@Test
	void aPathMatchesWhenAPositiveRuleMatchesAndNoNegatedRuleVetoesIt() {
		PathRuleSet rules = new PathRuleSet(List.of("config/**", "!config/fancymenu/**"));

		assertTrue(rules.matches("config/emoji.json"));
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

	@Test
	void globMatchingIsCaseSensitive() {
		PathRuleSet rules = new PathRuleSet(List.of("Mods/*.jar"));

		assertTrue(rules.matches("Mods/foo.jar"));
		assertFalse(rules.matches("mods/foo.jar"));
	}

	@Test
	void braceAndCharacterClassGlobsStayGlobs() {
		PathRuleSet rules = new PathRuleSet(List.of("{mods,config}/*.jar", "kubejs/[ab].js"));

		assertTrue(rules.matches("mods/sodium.jar"));
		assertTrue(rules.matches("config/foo.jar"));
		assertFalse(rules.matches("resourcepacks/pack.jar"));
		assertTrue(rules.matches("kubejs/a.js"));
		assertFalse(rules.matches("kubejs/c.js"));
	}

	@Test
	void aNegatedCharacterClassStaysNegated() {
		PathRuleSet rules = new PathRuleSet(List.of("mods/[!x]*.jar"));

		assertTrue(rules.matches("mods/sodium.jar"));
		assertFalse(rules.matches("mods/x.jasper"));
	}

	@Test
	void gitignoreDoubleStarPrefixAlsoMatchesTheRoot() {
		PathRuleSet hidden = new PathRuleSet(List.of("**/.*"));
		assertTrue(hidden.matches(".env"));
		assertTrue(hidden.matches("foo/.env"));

		PathRuleSet junk = new PathRuleSet(List.of("**/*.{tmp,disabled,bak}"));
		assertTrue(junk.matches("a.tmp"));
		assertTrue(junk.matches("dir/a.tmp"));
		assertTrue(junk.matches("a.disabled"));
		assertTrue(junk.matches("dir/a.bak"));

		PathRuleSet hiddenDirs = new PathRuleSet(List.of("**/.*/**"));
		assertTrue(hiddenDirs.matches(".git/config"));
		assertTrue(hiddenDirs.matches("foo/.git/config"));
	}
}
