package pl.skidam.automodpack_core.protocol;

import java.io.*;
import java.nio.file.*;

public final class LocalFileWriter {
	private LocalFileWriter() {}

	public static OutputStream open(Path destination) throws LocalStorageException {
		try {
			Path parent = destination.getParent();
			if (parent != null) Files.createDirectories(parent);
			return new LocalOutputStream(new BufferedOutputStream(
					Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)));
		} catch (IOException e) {
			throw new LocalStorageException("Failed to open local destination " + destination, e);
		}
	}

	private static final class LocalOutputStream extends FilterOutputStream {
		private LocalOutputStream(OutputStream output) {
			super(output);
		}

		@Override
		public void write(int value) throws IOException {
			try {
				out.write(value);
			} catch (IOException e) {
				throw new LocalStorageException("Failed to write local destination", e);
			}
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			try {
				out.write(bytes, offset, length);
			} catch (IOException e) {
				throw new LocalStorageException("Failed to write local destination", e);
			}
		}

		@Override
		public void flush() throws IOException {
			try {
				out.flush();
			} catch (IOException e) {
				throw new LocalStorageException("Failed to flush local destination", e);
			}
		}

		@Override
		public void close() throws IOException {
			try {
				out.close();
			} catch (IOException e) {
				throw new LocalStorageException("Failed to close local destination", e);
			}
		}
	}
}
