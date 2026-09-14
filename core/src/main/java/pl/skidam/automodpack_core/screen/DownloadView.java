package pl.skidam.automodpack_core.screen;

import java.util.List;

/**
 * The screen's window on one running download: progress polls, the file names currently in flight, and the cancel
 * action. The engine's transfer machinery stays behind it; screens hold this view, never the download engine.
 */
public interface DownloadView {
	boolean isRunning();

	boolean isCancelled();

	String getStage();

	double getPrecisePercentage();

	long getDownloadSpeed();

	long getETA();

	long acquired();

	long failed();

	/** Names of the files being downloaded right now, copied so the screen never iterates live engine state. */
	List<String> downloadingFileNames();

	void cancelAllAndShutdown() throws Exception;
}
