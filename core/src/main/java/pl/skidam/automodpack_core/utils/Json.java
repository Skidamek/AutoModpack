package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.*;

import pl.skidam.automodpack_core.protocol.NetUtils;

@SuppressWarnings("deprecation")
public class Json {
	public static JsonArray fromUrlAsArray(String url) {
		JsonElement element = null;

		try {
			HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setRequestProperty("User-Agent", NetUtils.USER_AGENT);
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(5000);
			connection.setDoOutput(true);
			connection.connect();
			if (connection.getResponseCode() == 200) {
				try (InputStreamReader isr = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
					JsonParser parser = new JsonParser(); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
					element = parser.parse(isr);
				}
			}
			connection.disconnect();
		} catch (SocketTimeoutException ignored) {
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (element != null && element.isJsonArray()) return element.getAsJsonArray();
		return null;
	}

	public static JsonObject fromModrinthUrl(final String requestUrl, List<String> listOfSha1) throws IOException {
		if (listOfSha1 == null || listOfSha1.isEmpty()) return null;

		JsonObject jsonObject = new JsonObject();
		jsonObject.add("hashes", new Gson().toJsonTree(listOfSha1));
		jsonObject.addProperty("algorithm", "sha1");

		final String body = jsonObject.toString();

		HttpURLConnection connection;
		URL url = new URL(requestUrl);
		connection = (HttpURLConnection) url.openConnection();
		connection.addRequestProperty("Content-Type", "application/json");
		connection.addRequestProperty("Accept", "application/json");
		connection.setConnectTimeout(3000);
		connection.setReadTimeout(10000);
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
		connection.connect();

		JsonElement element = null;

		int code = connection.getResponseCode();
		if (code == 200) {
			try (InputStreamReader isr = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
				element = new JsonParser().parse(isr); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
			}
		} else {
			LOGGER.warn("{} responded {} code", url, code);
		}

		connection.disconnect();

		if (element != null && !element.isJsonArray()) return element.getAsJsonObject();

		return null;

	}

}
