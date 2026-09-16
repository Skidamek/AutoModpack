package pl.skidam.automodpack_core.modpack.candidate;

public class CandidateBuildException extends Exception {
	public CandidateBuildException(String message) {
		super(message);
	}

	public CandidateBuildException(String message, Throwable cause) {
		super(message, cause);
	}
}
