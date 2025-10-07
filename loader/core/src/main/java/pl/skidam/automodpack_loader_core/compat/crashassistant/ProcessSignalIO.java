package pl.skidam.automodpack_loader_core.compat.crashassistant;

import pl.skidam.automodpack_core.GlobalVariables;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;

// Original source: https://github.com/KostromDan/Crash-Assistant/blob/1.19-1.20.1/common_config/src/main/java/dev/kostromdan/mods/crash_assistant/common_config/communication/ProcessSignalIO.java

/**
 * Utility class for simple file‑based inter‑process communication.
 * <p>
 * Each message is stored in the directory {@code local/crash_assistant} using two naming schemes:
 * </p>
 * <ul>
 *   <li>{@code <name>_pid<PID>.tmp} – per‑process files for point‑to‑point signals.</li>
 *   <li>{@code <name>.info} – shared information visible to any process.</li>
 * </ul>
 * <p>Available operations:</p>
 * <ul>
 *   <li>{@link #post(String, String)} – write arbitrary data to a per‑process file.</li>
 *   <li>{@link #post(String)} – write the current timestamp (ms) to a per‑process file.</li>
 *   <li>{@link #get(String, long)} – read data from a per‑process file.</li>
 *   <li>{@link #exists(String, long)} – check whether a per‑process file exists.</li>
 *   <li>{@link #postInfo(String, String)} – write arbitrary data to a shared <code>.info</code> file.</li>
 *   <li>{@link #getInfo(String)} – read data from a shared <code>.info</code> file.</li>
 *   <li>{@link #existsInfo(String)} – check whether a shared <code>.info</code> file exists.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class ProcessSignalIO {
    private static final Path BASE_DIR = Paths.get("local", "crash_assistant");

    public static long getCurrentProcessId() {
        return ProcessHandle.current().pid();
    }

    /**
     * Writes {@code data} into a file named {@code <name>_pid<CURRENT_PID>.tmp}.
     * If the target file already exists, it will be overwritten.
     */
    public static void post(String name, String data) {
        if (!Files.exists(BASE_DIR)) {
            return;
        }

        String fileName = name + "_pid" + getCurrentProcessId() + ".tmp";
        Path filePath = BASE_DIR.resolve(fileName);
        try {
            Files.writeString(
                    filePath,
                    data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            GlobalVariables.LOGGER.error("Error while saving data to {}", fileName, e);
        }
    }

    /** Convenience overload: writes the current system time (ms). */
    public static void post(String name) {
        post(name, Long.toString(System.currentTimeMillis()));
    }

    /**
     * Writes {@code data} into a file named {@code <name>_pid<pid>.tmp} for the specified process.
     * If the target file already exists, it will be overwritten.
     *
     * @param name logical identifier for the signal
     * @param data payload to store
     * @param pid  process ID to target
     */
    public static void postAsOtherProcess(String name, String data, long pid) {
        if (!Files.exists(BASE_DIR)) {
            return;
        }

        String fileName = name + "_pid" + pid + ".tmp";
        Path filePath = BASE_DIR.resolve(fileName);
        try {
            Files.writeString(
                    filePath,
                    data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            GlobalVariables.LOGGER.error("Error while saving data to {}", fileName, e);
        }
    }

    /**
     * Convenience overload: writes the current system time (ms) to the specified process's file.
     *
     * @param name logical identifier for the signal
     * @param pid  process ID to target
     */
    public static void postAsOtherProcess(String name, long pid) {
        postAsOtherProcess(name, Long.toString(System.currentTimeMillis()), pid);
    }

    /** Reads the contents of {@code <name>_pid<pid>.tmp}. */
    public static Optional<String> get(String name, long pid) {
        String fileName = name + "_pid" + pid + ".tmp";
        Path filePath = BASE_DIR.resolve(fileName);

        if (!Files.isReadable(filePath)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String content = new String(bytes, StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (IOException e) {
            GlobalVariables.LOGGER.error("Error while reading data from {}", fileName, e);
            return Optional.empty();
        }
    }

    /** Checks whether {@code <name>_pid<pid>.tmp} exists. */
    public static boolean exists(String name, long pid) {
        String fileName = name + "_pid" + pid + ".tmp";
        Path filePath = BASE_DIR.resolve(fileName);
        return Files.isRegularFile(filePath);
    }

    /* ────────────────────────────────────────────────────── */
    /* Shared .info files                                    */
    /* ────────────────────────────────────────────────────── */

    /**
     * Writes {@code data} into a shared file named {@code <name>.info}.
     * The file is overwritten if it already exists.
     *
     * @param name logical identifier (e.g. {@code "version"})
     * @param data payload to store
     */
    public static void postInfo(String name, String data) {
        if (!Files.exists(BASE_DIR)) {
            return;
        }

        String fileName = name + ".info";
        Path filePath = BASE_DIR.resolve(fileName);
        try {
            Files.writeString(
                    filePath,
                    data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            GlobalVariables.LOGGER.error("Error while saving info to {}", fileName, e);
        }
    }

    /**
     * Reads the contents of the shared file {@code <name>.info}.
     *
     * @param name logical identifier
     * @return optional containing data if readable; otherwise empty
     */
    public static Optional<String> getInfo(String name) {
        String fileName = name + ".info";
        Path filePath = BASE_DIR.resolve(fileName);

        if (!Files.isReadable(filePath)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String content = new String(bytes, StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (IOException e) {
            GlobalVariables.LOGGER.error("Error while reading info from {}", fileName, e);
            return Optional.empty();
        }
    }

    /** Checks whether {@code <name>.info} exists. */
    public static boolean existsInfo(String name) {
        String fileName = name + ".info";
        Path filePath = BASE_DIR.resolve(fileName);
        return Files.isRegularFile(filePath);
    }
}