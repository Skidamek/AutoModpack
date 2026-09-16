package pl.skidam.automodpack.client.ui;

final class UiFormat {
	private UiFormat() {}

	static String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024L * 1024L) return (bytes / 1024) + " KiB";
		if (bytes < 1024L * 1024L * 1024L) return (bytes / (1024L * 1024L)) + " MiB";
		return (bytes / (1024L * 1024L * 1024L)) + " GiB";
	}
}
