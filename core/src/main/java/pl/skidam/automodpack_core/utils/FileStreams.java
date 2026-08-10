package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Opens read-only file handles with the JDK's platform sharing semantics. */
public final class FileStreams {
	private FileStreams() {}

	public static InputStream open(Path path) throws IOException {
		return Files.newInputStream(path, StandardOpenOption.READ);
	}

	public static FileChannel openChannel(Path path) throws IOException {
		return FileChannel.open(path, StandardOpenOption.READ);
	}

	public static FileChannel openChannelNoFollow(Path path) throws IOException {
		return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
	}
}
