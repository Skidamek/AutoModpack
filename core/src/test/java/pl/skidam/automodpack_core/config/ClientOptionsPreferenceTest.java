package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ClientOptionsPreferenceTest {
	@Test
	void defaultsToFalseAndRejectsAmbiguousValues() throws Exception {
		Path options = Files.createTempFile("automodpack-options", ".txt");
		assertFalse(ClientOptionsPreference.readSkipReview(options));
		Files.writeString(options, "automodpack.skipReview:true\nautomodpack.skipReview:false\n", StandardCharsets.UTF_8);
		assertFalse(ClientOptionsPreference.readSkipReview(options));
		Files.writeString(options, "automodpack.skipReview:not-a-boolean\n", StandardCharsets.UTF_8);
		assertFalse(ClientOptionsPreference.readSkipReview(options));
	}

	@Test
	void rewritesOnePreferenceLineAndPreservesVanillaOptions() throws Exception {
		Path options = Files.createTempFile("automodpack-options", ".txt");
		Files.writeString(options, "version:42\nautoJump:true\nautomodpack.skipReview:false\nautomodpack.skipReview:true\n", StandardCharsets.UTF_8);
		ClientOptionsPreference.writeSkipReview(options, true);
		ClientOptionsPreference.writeSkipReview(options, false);

		String contents = Files.readString(options, StandardCharsets.UTF_8);
		assertTrue(contents.contains("version:42\n"));
		assertTrue(contents.contains("autoJump:true\n"));
		assertEquals(1, contents.lines().filter(line -> line.startsWith(ClientOptionsPreference.SKIP_REVIEW_KEY + ":")).count());
		assertEquals("automodpack.skipReview:false", contents.lines().filter(line -> line.startsWith(ClientOptionsPreference.SKIP_REVIEW_KEY + ":")).findFirst().orElseThrow());
		assertFalse(ClientOptionsPreference.readSkipReview(options));
	}
}
