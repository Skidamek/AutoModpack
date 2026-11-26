import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Strips LocalVariableTable and LocalVariableTypeTable attributes from JVM class files while
 * keeping LineNumberTable and SourceFile intact so stack traces are unaffected. Used on shaded
 * third-party classes in the outer jar; our own modules already compile with -g:lines,source.
 */
internal object ClassLocalVarStripper {
    private val STRIPPED = setOf("LocalVariableTable", "LocalVariableTypeTable")

    fun strip(bytes: ByteArray): ByteArray {
        if (bytes.size < 10) return bytes
        val buf = ByteBuffer.wrap(bytes)
        if (buf.getInt(0) != 0xCAFEBABE.toInt()) return bytes

        var pos = 8
        val cpCount = buf.getShort(pos).toInt() and 0xFFFF
        pos += 2
        val utf8 = arrayOfNulls<String>(cpCount)
        var i = 1
        while (i < cpCount) {
            when (val tag = bytes[pos++].toInt() and 0xFF) {
                1 -> {
                    val len = buf.getShort(pos).toInt() and 0xFFFF
                    pos += 2
                    utf8[i] = String(bytes, pos, len, StandardCharsets.UTF_8)
                    pos += len
                }
                3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4
                5, 6 -> {
                    pos += 8
                    i++
                }
                7, 8, 16, 19, 20 -> pos += 2
                15 -> pos += 3
                else -> return bytes
            }
            i++
        }

        // Skip access_flags, this_class, super_class, and interfaces
        pos += 6
        val ifaceCount = buf.getShort(pos).toInt() and 0xFFFF
        pos += 2 + ifaceCount * 2

        val headerEnd = pos
        val out = ByteArrayOutputStream(bytes.size)
        val dos = DataOutputStream(out)
        out.write(bytes, 0, headerEnd)

        fun rewriteAttrs(start: Int): Pair<ByteArray, Int> {
            var p = start
            val count = buf.getShort(p).toInt() and 0xFFFF
            p += 2
            val kept = ArrayList<ByteArray>(count)
            repeat(count) {
                val nameIdx = buf.getShort(p).toInt() and 0xFFFF
                val attrLen = buf.getInt(p + 2)
                val bodyStart = p + 6
                val bodyEnd = bodyStart + attrLen
                val name = if (nameIdx < cpCount) utf8[nameIdx] else null
                when {
                    name in STRIPPED -> Unit
                    name == "Code" -> {
                        val codeLen = buf.getInt(bodyStart + 4)
                        val excPos = bodyStart + 8 + codeLen
                        val excLen = buf.getShort(excPos).toInt() and 0xFFFF
                        val subPos = excPos + 2 + excLen * 8
                        val (newSub, _) = rewriteAttrs(subPos)
                        val newBodyLen = (subPos - bodyStart) + newSub.size
                        val codeAttr = ByteArrayOutputStream(6 + newBodyLen)
                        val cdos = DataOutputStream(codeAttr)
                        cdos.writeShort(nameIdx)
                        cdos.writeInt(newBodyLen)
                        codeAttr.write(bytes, bodyStart, subPos - bodyStart)
                        codeAttr.write(newSub)
                        kept.add(codeAttr.toByteArray())
                    }
                    else -> kept.add(bytes.copyOfRange(p, bodyEnd))
                }
                p = bodyEnd
            }
            val attrOut = ByteArrayOutputStream()
            val ados = DataOutputStream(attrOut)
            ados.writeShort(kept.size)
            for (entry in kept) attrOut.write(entry)
            return attrOut.toByteArray() to p
        }

        // Fields and methods
        repeat(2) {
            val memberCount = buf.getShort(pos).toInt() and 0xFFFF
            dos.writeShort(memberCount)
            pos += 2
            repeat(memberCount) {
                out.write(bytes, pos, 6)
                pos += 6
                val (attrs, next) = rewriteAttrs(pos)
                out.write(attrs)
                pos = next
            }
        }
        val (classAttrs, _) = rewriteAttrs(pos)
        out.write(classAttrs)
        return out.toByteArray()
    }
}
