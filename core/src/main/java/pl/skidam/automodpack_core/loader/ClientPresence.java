package pl.skidam.automodpack_core.loader;

/** What a joining client is allowed to do with this jar, as a fact about that file rather than about this JVM. */
public enum ClientPresence {
	/** The loader would kick a client that lacks this jar. */
	REQUIRED,
	/** The loader lets a client run without this jar, and the jar does not name itself server-only. */
	OPTIONAL,
	/** The jar's own metadata names it server-only. */
	SERVER_ONLY;
}
