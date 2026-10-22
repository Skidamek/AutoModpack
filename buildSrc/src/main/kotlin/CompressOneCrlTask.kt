import com.github.luben.zstd.Zstd
import io.airlift.compress.zstd.ZstdInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayInputStream

/**
 * Compresses the human-edited OneCRL source into the runtime resource. The source stays readable under
 * core/src/main/onecrl/; the shipped resource is a one-shot zstd level-20 frame at the jar root, the same frame shape
 * the runtime's aircompressor decoder accepts. The decode check below is the build-time tripwire: a frame the client
 * cannot read fails here, not on a player's launch.
 */
@CacheableTask
abstract class CompressOneCrlTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun compress() {
        val bytes = source.get().asFile.readBytes()
        if (bytes.isEmpty()) throw GradleException("OneCRL source ${source.get().asFile} is empty")
        val frame = Zstd.compress(bytes, ZSTD_LEVEL)
        verifyRuntimeDecodable(frame, bytes)
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeBytes(frame)
        println("Compressed OneCRL snapshot: ${bytes.size} -> ${frame.size} bytes (zstd level $ZSTD_LEVEL)")
    }

    private fun verifyRuntimeDecodable(frame: ByteArray, source: ByteArray) {
        val decoded = ZstdInputStream(ByteArrayInputStream(frame)).use { it.readAllBytes() }
        if (!decoded.contentEquals(source)) throw GradleException("The OneCRL snapshot is not decodable by the runtime's aircompressor decoder")
    }
}
