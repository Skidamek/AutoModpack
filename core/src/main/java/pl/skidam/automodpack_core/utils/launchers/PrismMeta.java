package pl.skidam.automodpack_core.utils.launchers;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.HTTP_TIMEOUT_MILLIS;
import static pl.skidam.automodpack_core.protocol.NetUtils.USER_AGENT;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

// Prism and MultiMC forks resolve loader components from this meta server; a synced version missing here stalls or breaks the next launch.
public class PrismMeta {

	private static final String META_URL = "https://meta.prismlauncher.org/v1/";

	public static boolean isVersionResolvable(String loaderType, String loaderVersion) {
		String uid = MultiMCMeta.componentUid(loaderType);
		if (uid == null || loaderVersion == null || loaderVersion.isBlank()) return false;
		String url = META_URL + uid + "/" + loaderVersion + ".json";
		try {
			HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
			connection.setReadTimeout(HTTP_TIMEOUT_MILLIS);
			int code = connection.getResponseCode();
			connection.disconnect();
			return code == 200;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not reach the launcher meta server at: {}", url, e);
			return false;
		}
	}
}
