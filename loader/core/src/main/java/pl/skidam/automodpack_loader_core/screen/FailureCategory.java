package pl.skidam.automodpack_loader_core.screen;

/** Stable categories used to explain an operational failure without exposing implementation details. */
public enum FailureCategory {
	CONNECTION("connection"),
	HOST("host"),
	STORAGE("storage"),
	UPDATE("update"),
	CORRUPT_STATE("corruptState"),
	SECURITY("security"),
	INTERNAL("internal");

	private final String key;

	FailureCategory(String key) {
		this.key = key;
	}

	public String key() {
		return key;
	}

	public String translationKey() {
		return "automodpack.error.category." + key;
	}
}
