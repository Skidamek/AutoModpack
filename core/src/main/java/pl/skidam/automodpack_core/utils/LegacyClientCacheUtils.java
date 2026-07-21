package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.clientDeletionTimeStamps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

public class LegacyClientCacheUtils {
	private static final Jsons.ClientDeletedNonModpackFilesTimestamps clientDeletedNonModpackFilesTimestamps = ConfigTools.readOrCreate(clientDeletionTimeStamps,
			Jsons.ClientDeletedNonModpackFilesTimestamps.class, Jsons.ClientDeletedNonModpackFilesTimestamps::new);

	private static void write(Path path, Object value) {
		try {
			ConfigTools.writeAtomic(path, value);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save client cache state", e);
		}
	}

	public static boolean wasThisTimestampEvaluatedBefore(String timestamp) {
		if (clientDeletedNonModpackFilesTimestamps == null) return false;
		return clientDeletedNonModpackFilesTimestamps.timestamps.contains(timestamp);
	}

	public static Set<String> getEvaluatedDeletionTimestamps() {
		if (clientDeletedNonModpackFilesTimestamps == null) return Set.of();
		return Set.copyOf(clientDeletedNonModpackFilesTimestamps.timestamps);
	}

	public static void markTimestampAsEvaluated(String timestamp) {
		if (clientDeletedNonModpackFilesTimestamps == null) return;
		clientDeletedNonModpackFilesTimestamps.timestamps.add(timestamp);
	}

	public static void saveDeletedFilesTimestamps() {
		if (clientDeletedNonModpackFilesTimestamps == null) return;
		write(clientDeletionTimeStamps, clientDeletedNonModpackFilesTimestamps);
	}
}
