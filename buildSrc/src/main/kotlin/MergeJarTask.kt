import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

abstract class MergeJarTask : DefaultTask() {
    @get:Input
    abstract val mergedDirPath: Property<String>

    @get:Input
    abstract val rootProjectPath: Property<String>

    @get:Input
    abstract val libsPath: Property<String>

    @get:Internal
    abstract val buildDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun mergeJars() {
        val mergedDir = File(mergedDirPath.get())
        mergedDir.mkdirs()

        val buildDirLibs = buildDirectory.get().dir("libs").asFile
        val jarToMerge = buildDirLibs.listFiles()
            ?.firstOrNull { file -> file.isFile && !file.name.endsWith("-sources.jar") && file.name.endsWith(".jar") }
            ?: error("No jar found to merge in build/libs directory! ${buildDirLibs.absolutePath}")

        val time = System.currentTimeMillis()
        println("Found $jarToMerge to merge. Merging...")

        val loaderModule = getLoaderModuleName(jarToMerge.name)

        val loaderBuildDir = File(rootProjectPath.get(), "loader/${loaderModule.replace("-", "/")}/build/libs")
        val loaderFile = loaderBuildDir.listFiles()
            ?.single { it.isFile && !it.name.endsWith("-sources.jar") && it.name.endsWith(".jar") }
            ?: error("No loader jar found in ${loaderBuildDir.absolutePath}")

        val libsDir = File(libsPath.get())
        val zstdFile = libsDir.listFiles()
            ?.firstOrNull { file -> file.isFile && file.name.startsWith("zstd-jni-") && file.name.endsWith(".jar") }
            ?: error("No zstd-jni-*.jar found in libs directory! ${libsDir.absolutePath}")

        val finalJar = File(mergedDir, jarToMerge.name)

        // Merge jars inline to avoid script reference issues
        val seen = mutableSetOf<String>()
        ZipOutputStream(FileOutputStream(finalJar).buffered()).use { zipStream ->
            ZipInputStream(FileInputStream(loaderFile).buffered()).use { baseStream ->
                generateSequence { baseStream.nextEntry }
                    .forEach { entry ->
                        if (seen.add(entry.name) && !entry.isDirectory) {
                            zipStream.putNextEntry(ZipEntry(entry.name))
                            baseStream.copyTo(zipStream)
                            zipStream.closeEntry()
                        }
                    }
            }

            // Add mod jar
            zipStream.putNextEntry(ZipEntry("META-INF/jarjar/automodpack-mod.jar"))
            FileInputStream(jarToMerge).buffered().use { it.copyTo(zipStream) }
            zipStream.closeEntry()

            // Add zstd jar
            zipStream.putNextEntry(ZipEntry("META-INF/jarjar/zstd-jni.jar"))
            FileInputStream(zstdFile).buffered().use { it.copyTo(zipStream) }
            zipStream.closeEntry()
        }

        outputJar.get().asFile.writeText(finalJar.absolutePath)
        println("Merged: ${jarToMerge.name} into: ${finalJar.name} from: ${loaderFile.name} Took: ${System.currentTimeMillis() - time}ms")
    }
}
