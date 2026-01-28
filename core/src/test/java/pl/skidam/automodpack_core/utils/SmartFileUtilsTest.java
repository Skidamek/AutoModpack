package pl.skidam.automodpack_core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.*;

class SmartFileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void testSHA1Hash_KnownValue() throws IOException, NoSuchAlgorithmException {
        Path file = tempDir.resolve("test-hash.txt");
        String content = "test content 2137!";
        Files.writeString(file, content);

        String actualHash = SmartFileUtils.getHash(file);

        assertNotNull(actualHash, "Hash should not be null");
        assertEquals("16883d77e42fcb574c70e31cda49b3f955a48be8", actualHash, "getHash should return the correct SHA-1 hash");
    }

    @Test
    void testMurmurHash_KnownValue() throws IOException {
        Path file = tempDir.resolve("murmur-test.txt");
        Files.writeString(file, "test content 2137!");

        String actualHash = SmartFileUtils.getCurseforgeMurmurHash(file);

        assertEquals("3151456706", actualHash, "MurmurHash for 'test' should match known constant");
    }

    @Test
    void testMurmurHash_IgnoresWhitespace() throws IOException {
        Path cleanFile = tempDir.resolve("clean.txt");
        Path messyFile = tempDir.resolve("messy.txt");

        String cleanContent = "test";
        String messyContent = " t\te\ns\rt ";

        Files.writeString(cleanFile, cleanContent);
        Files.writeString(messyFile, messyContent);

        String cleanHash = SmartFileUtils.getCurseforgeMurmurHash(cleanFile);
        String messyHash = SmartFileUtils.getCurseforgeMurmurHash(messyFile);

        assertEquals(cleanHash, messyHash, "Hashes should be identical despite whitespace differences");
        assertEquals("2667173943", messyHash, "Messy file should still hash to the value of 'test'");
    }

    @Test
    void testGetHash_NonExistentFile() {
        Path missingFile = tempDir.resolve("does-not-exist.txt");

        String result = SmartFileUtils.getHash(missingFile);
        
        assertNull(result, "Should return null for missing file");
    }
}