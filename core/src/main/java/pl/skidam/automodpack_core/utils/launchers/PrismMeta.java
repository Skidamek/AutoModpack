package pl.skidam.automodpack_core.utils.launchers;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.util.Map;

import pl.skidam.automodpack_core.utils.HttpClientPool;

// Prism and MultiMC forks resolve loader components from this meta server; a synced version missing here stalls or breaks the next launch.
public class PrismMeta {

	private static final String META_URL = "https://meta.prismlauncher.org/v1/";

	public static boolean isVersionResolvable(String loaderType, String loaderVersion) {
		String uid = MultiMCMeta.componentUid(loaderType);
		if (uid == null || loaderVersion == null || loaderVersion.isBlank()) return false;
		return isResolvable(META_URL + uid + "/" + loaderVersion + ".json");
	}

	/** The full request url is injectable so tests can serve the meta server locally. */
	static boolean isResolvable(String url) {
		try {
			return HttpClientPool.request(url, Map.of(), null, true).statusCode() == 200;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not reach the launcher meta server at: {}", url, e);
			return false;
		}
	}
}
