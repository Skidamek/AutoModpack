import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/**
 * Writes standard PKZIP 2.0 archives without Extended Timestamp (0x5455) extra fields or bit-3
 * Data Descriptors. Every entry carries its CRC and sizes directly in the Local File Header with a
 * deterministic DOS epoch timestamp (1980-01-01 00:00:00).
 */
internal class TightZipWriter(
    private val out: OutputStream,
) : Closeable {
    private data class Record(
        val nameBytes: ByteArray,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Long,
    )

    private val records = mutableListOf<Record>()
    private var offset = 0L

    fun writeStored(name: String, raw: ByteArray) {
        val crc = CRC32().apply { update(raw) }.value
        writePrecompressed(name, ZipEntry.STORED, crc, raw.size, raw)
    }

    fun writePrecompressed(
        name: String,
        method: Int,
        crc: Long,
        uncompressedSize: Int,
        payload: ByteArray,
    ) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val flags = if (nameBytes.size != name.length) 0x0800 else 0
        val entryOffset = offset

        val lfh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        lfh.putInt(0x04034B50) // local file header signature
        lfh.putShort(20) // version needed to extract (2.0)
        lfh.putShort(flags.toShort())
        lfh.putShort(method.toShort())
        lfh.putShort(DOS_TIME_ZERO)
        lfh.putShort(DOS_DATE_EPOCH)
        lfh.putInt(crc.toInt())
        lfh.putInt(payload.size)
        lfh.putInt(uncompressedSize)
        lfh.putShort(nameBytes.size.toShort())
        lfh.putShort(0) // extra field length

        out.write(lfh.array())
        out.write(nameBytes)
        out.write(payload)
        offset += 30L + nameBytes.size + payload.size

        records.add(Record(nameBytes, flags, method, crc, payload.size, uncompressedSize, entryOffset))
    }

    override fun close() {
        require(records.size <= 0xFFFF) { "Too many ZIP entries for non-ZIP64 archive: ${records.size}" }
        val cdStart = offset
        var cdSize = 0L
        for (rec in records) {
            val cd = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            cd.putInt(0x02014B50) // central file header signature
            cd.putShort(20) // version made by
            cd.putShort(20) // version needed
            cd.putShort(rec.flags.toShort())
            cd.putShort(rec.method.toShort())
            cd.putShort(DOS_TIME_ZERO)
            cd.putShort(DOS_DATE_EPOCH)
            cd.putInt(rec.crc.toInt())
            cd.putInt(rec.compressedSize)
            cd.putInt(rec.uncompressedSize)
            cd.putShort(rec.nameBytes.size.toShort())
            cd.putShort(0) // extra length
            cd.putShort(0) // comment length
            cd.putShort(0) // disk number start
            cd.putShort(0) // internal file attributes
            cd.putInt(0) // external file attributes
            cd.putInt(rec.localHeaderOffset.toInt())
            out.write(cd.array())
            out.write(rec.nameBytes)
            cdSize += 46L + rec.nameBytes.size
        }

        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(0x06054B50) // end of central dir signature
        eocd.putShort(0) // disk number
        eocd.putShort(0) // disk with central dir
        eocd.putShort(records.size.toShort())
        eocd.putShort(records.size.toShort())
        eocd.putInt(cdSize.toInt())
        eocd.putInt(cdStart.toInt())
        eocd.putShort(0) // comment length
        out.write(eocd.array())
        out.flush()
        out.close()
    }

    private companion object {
        const val DOS_TIME_ZERO: Short = 0x0000
        const val DOS_DATE_EPOCH: Short = 0x0021 // 1980-01-01
    }
}
