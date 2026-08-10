package pl.skidam.automodpack.client.ui;

import pl.skidam.automodpack_core.update.UpdatePlan;

final class UiFormat {
	private UiFormat() {}

	static String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024L * 1024L) return (bytes / 1024) + " KiB";
		if (bytes < 1024L * 1024L * 1024L) return (bytes / (1024L * 1024L)) + " MiB";
		return (bytes / (1024L * 1024L * 1024L)) + " GiB";
	}

	static String filePath(UpdatePlan.FileKey file) {
		return rootLabel(file.root()) + "/" + file.relativePath();
	}

	static String changePath(UpdatePlan.FileKey file) {
		return file.root() == UpdatePlan.Root.PROJECTION ? file.relativePath() : filePath(file);
	}

	static String rootLabel(UpdatePlan.Root root) {
		return switch (root) {
			case PROJECTION -> "active";
			case OVERLAY -> "editable";
			case GAME_DIR -> "game";
		};
	}
}
