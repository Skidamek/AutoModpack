package pl.skidam.automodpack_core.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransientAttentionTest {
	@Test
	void supersededTokensCannotReopenTheWait() {
		TransientAttention attention = new TransientAttention();
		long first = attention.begin();
		assertTrue(attention.isCurrent(first));
		attention.supersede();
		assertFalse(attention.isCurrent(first));
		long second = attention.begin();
		assertTrue(attention.isCurrent(second));
		assertFalse(attention.isCurrent(first));
	}
}
