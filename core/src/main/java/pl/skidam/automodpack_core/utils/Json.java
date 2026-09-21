package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@SuppressWarnings("deprecation")
public class Json {

	public static JsonArray fromUrlAsArray(String url) {
		JsonElement element = element(url, Map.of(), null);
		if (element != null && element.isJsonArray()) return element.getAsJsonArray();
		return null;
	}

	public static JsonObject fromModrinthUrl(final String requestUrl, List<String> listOfSha1) {
		if (listOfSha1 == null || listOfSha1.isEmpty()) return null;

		JsonObject jsonObject = new JsonObject();
		jsonObject.add("hashes", new Gson().toJsonTree(listOfSha1));
		jsonObject.addProperty("algorithm", "sha1");

		JsonElement element = element(requestUrl, Map.of("Content-Type", "application/json", "Accept", "application/json"),
				jsonObject.toString().getBytes(StandardCharsets.UTF_8));
		if (element != null && !element.isJsonArray()) return element.getAsJsonObject();
		return null;
	}

	/** The body parsed as JSON on 200; null on any other status or transport failure - both are already retried and status-logged by the pool. */
	private static JsonElement element(String url, Map<String, String> headers, byte[] body) {
		try {
			HttpResponse<byte[]> response = HttpClientPool.request(url, headers, body, true);
			if (response.statusCode() == 200) {
				try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(response.body()), StandardCharsets.UTF_8)) {
					return new JsonParser().parse(reader); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
				}
			}
			LOGGER.warn("{} responded {} code", url, response.statusCode());
		} catch (IOException e) {
			LOGGER.warn("Failed to fetch {}", url, e);
		}
		return null;
	}
}
