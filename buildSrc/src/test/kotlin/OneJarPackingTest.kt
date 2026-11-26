import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry

class OneJarPackingTest {
    @Test
    fun tightZipWriterRoundTripsStoredAndDeflatedWithoutExtraFields(@TempDir dir: Path) {
        val jarPath = dir.resolve("test.jar").toFile()
        val storedPayload = "hello stored".toByteArray(StandardCharsets.UTF_8)
        val deflatedRaw = "hello deflated ".repeat(200).toByteArray(StandardCharsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true).apply {
            setInput(deflatedRaw)
            finish()
        }
        val buf = ByteArray(deflatedRaw.size + 64)
        val deflatedLen = deflater.deflate(buf)
        deflater.end()
        val deflatedPayload = buf.copyOf(deflatedLen)
        val deflatedCrc = CRC32().apply { update(deflatedRaw) }.value

        TightZipWriter(FileOutputStream(jarPath)).use { writer ->
            writer.writeStored("a/stored.txt", storedPayload)
            writer.writePrecompressed("b/deflated.txt", ZipEntry.DEFLATED, deflatedCrc, deflatedRaw.size, deflatedPayload)
        }

        JarFile(jarPath).use { jar ->
            val stored = jar.getEntry("a/stored.txt")
            assertEquals(ZipEntry.STORED, stored.method)
            assertNull(stored.extra)
            assertArrayEquals(storedPayload, jar.getInputStream(stored).readBytes())

            val deflated = jar.getEntry("b/deflated.txt")
            assertEquals(ZipEntry.DEFLATED, deflated.method)
            assertNull(deflated.extra)
            assertArrayEquals(deflatedRaw, jar.getInputStream(deflated).readBytes())
        }
    }
}
