package pl.skidam.automodpack_core.protocol;

import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The URL contract's one route table: the two reserved document names and the content-addressed objects. */
public final class ContractRoutes {

	private ContractRoutes() {}

	/** The hosting key a request target names, or null when the target names no route. */
	public static String key(String target) {
		if (target.equals("/" + GenerationHosting.HEAD_DOCUMENT_KEY)) return GenerationHosting.HEAD_DOCUMENT_KEY;
		if (target.equals("/" + GenerationHosting.JOURNAL_KEY)) return GenerationHosting.JOURNAL_KEY;
		if (target.startsWith("/objects/")) {
			String sha1 = target.substring("/objects/".length());
			return HashUtils.isSha1(sha1) ? HashUtils.normalizeSha1(sha1) : null;
		}
		return null;
	}

	/** True for the two reserved documents, whose validator etag comes from the server's memo rather than from the key itself. */
	public static boolean isDocument(String key) {
		return key.equals(GenerationHosting.HEAD_DOCUMENT_KEY) || key.equals(GenerationHosting.JOURNAL_KEY);
	}
}
