package pl.skidam.automodpack_core.launchers;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.util.Map;

import pl.skidam.automodpack_core.utils.HttpClientPool;

// Prism and MultiMC forks resolve components from this meta server; a switched version missing here stalls or breaks the next launch.
public class PrismMeta {

	private static final String META_URL = "https://meta.prismlauncher.org/v1/";

	/** Whether the meta server serves the component version. False also on network failure: fail closed rather than write an unresolvable version. */
	public static boolean isVersionResolvable(String componentUid, String version) {
		if (componentUid == null || version == null || version.isBlank()) return false;
		return isResolvable(META_URL + componentUid + "/" + version + ".json");
	}

	/** The full request url is injectable so tests can serve the meta server locally. */
	static boolean isResolvable(String url) {
		try {
			int status = HttpClientPool.request(url, Map.of(), null, true).statusCode();
			if (status == 200) return true;
			LOGGER.warn("The launcher meta server at {} answered HTTP {}", url, status);
			return false;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not reach the launcher meta server at: {}", url, e);
			return false;
		}
	}
}
