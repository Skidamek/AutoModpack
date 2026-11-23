package pl.skidam.automodpack_core.winprobe;

/** The Windows kernel contract cannot be forced on this process. */
public final class WinProbeFailed extends RuntimeException {
	public WinProbeFailed(String message) {
		super(message);
	}
}
