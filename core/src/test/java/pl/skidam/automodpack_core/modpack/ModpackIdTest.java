package pl.skidam.automodpack_core.modpack;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class ModpackIdTest {
	@Test
	void acceptsOnlySevenCharacterLowercaseBase36Ids() {
		String generated = ModpackId.generate();
		assertTrue(ModpackId.isValid(generated));
		assertEquals(7, generated.length());
		assertFalse(ModpackId.isValid(generated.toUpperCase(Locale.ROOT)));
		assertFalse(ModpackId.isValid("2adfc750-9dd4-5b11-9468-053404e1d74d"));
		assertFalse(ModpackId.isValid("abcdef"));
		assertThrows(IllegalArgumentException.class, () -> ModpackId.requireValid("not-an-id"));
	}
}
