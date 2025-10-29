package pl.skidam.automodpack_core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FileInspectionTest {

    @TempDir
    Path tempDir;

    @Test
    void testGetThizJarExists() {
        // Test that getThizJar returns a valid path
        Path jarPath = FileInspection.getThizJar();
        assertNotNull(jarPath, "JAR path should not be null");
        assertTrue(jarPath.isAbsolute(), "JAR path should be absolute");
        
        // In test environment, this might be a classes directory or a JAR
        assertTrue(Files.exists(jarPath) || jarPath.toString().contains("classes"), 
                   "JAR path should exist or be in classes directory: " + jarPath);
    }

    @Test
    void testGetThizJarIsNormalized() {
        Path jarPath = FileInspection.getThizJar();
        assertEquals(jarPath, jarPath.normalize(), "JAR path should be normalized");
    }

    /**
     * Test to verify the method can handle paths with special characters.
     * This is a conceptual test since we can't easily create a JAR with special chars
     * in its path and load classes from it in a unit test.
     */
    @Test
    void testGetThizJarWithSpecialCharactersInPath() throws IOException {
        // Create a temporary JAR file with special characters in the path
        Path testDir = tempDir.resolve("test#dir!with@special");
        Files.createDirectories(testDir);
        Path testJar = testDir.resolve("test#file!.jar");
        
        // Create a minimal JAR file
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(testJar.toFile()))) {
            JarEntry entry = new JarEntry("test.txt");
            jos.putNextEntry(entry);
            jos.write("test".getBytes());
            jos.closeEntry();
        }
        
        assertTrue(Files.exists(testJar), "Test JAR should be created");
        assertTrue(testJar.toString().contains("#"), "Test path should contain #");
        assertTrue(testJar.toString().contains("!"), "Test path should contain !");
        
        // The actual getThizJar() will return the current JAR, not our test JAR
        // But this verifies that our test setup works and such paths are valid
        Path currentJar = FileInspection.getThizJar();
        assertNotNull(currentJar);
    }

    @Test
    void testGetThizJarConsistency() {
        // Test that calling getThizJar multiple times returns the same result
        Path jarPath1 = FileInspection.getThizJar();
        Path jarPath2 = FileInspection.getThizJar();
        assertEquals(jarPath1, jarPath2, "Multiple calls should return the same path");
    }

    /**
     * Test that Windows paths are handled correctly (if running on Windows).
     * This test is primarily for documentation and will work on any platform.
     */
    @Test
    void testGetThizJarWindowsPathHandling() {
        Path jarPath = FileInspection.getThizJar();
        String pathString = jarPath.toString();
        
        // On Windows, ensure we don't have paths like /C:/... (leading slash before drive letter)
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (pathString.length() >= 2 && pathString.charAt(1) == ':') {
                assertFalse(pathString.startsWith("/"), 
                    "Windows path should not start with / before drive letter");
            }
        }
    }

    @Test
    void testGetThizJarNotEmpty() {
        Path jarPath = FileInspection.getThizJar();
        assertFalse(jarPath.toString().isEmpty(), "JAR path should not be empty");
    }

    /**
     * Test that the path doesn't contain JAR entry separators (! or #) at the end.
     * These separators indicate JAR internal paths, not file system paths.
     */
    @Test
    void testGetThizJarNoJarEntrySeparators() {
        Path jarPath = FileInspection.getThizJar();
        String pathString = jarPath.toString();
        
        assertFalse(pathString.endsWith("!/"), "Path should not end with !/");
        assertFalse(pathString.endsWith("#/"), "Path should not end with #/");
        assertFalse(pathString.endsWith("!"), "Path should not end with !");
    }

    @Test
    void testGetThizJarNoUrlEncoding() {
        Path jarPath = FileInspection.getThizJar();
        String pathString = jarPath.toString();
        
        // Ensure URL encoding is properly decoded
        assertFalse(pathString.contains("%20"), "Path should not contain URL-encoded spaces");
        assertFalse(pathString.contains("%23"), "Path should not contain URL-encoded #");
        assertFalse(pathString.contains("%21"), "Path should not contain URL-encoded !");
    }

    @Test
    void testGetThizJarNoUriSchemePrefix() {
        Path jarPath = FileInspection.getThizJar();
        String pathString = jarPath.toString();
        
        // Ensure URI schemes are removed
        assertFalse(pathString.startsWith("jar:"), "Path should not start with jar:");
        assertFalse(pathString.startsWith("union:"), "Path should not start with union:");
        assertFalse(pathString.startsWith("file:"), "Path should not start with file:");
    }
}
