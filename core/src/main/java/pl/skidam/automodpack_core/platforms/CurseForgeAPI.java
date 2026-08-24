package pl.skidam.automodpack_core.platforms;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.protocol.NetUtils;

public record CurseForgeAPI(String requestUrl, String downloadUrl, String fileVersion, String fileName, String fileSize, String releaseType, String murmurHash,
		String sha1Hash, int modId, String projectPageUrl) {

	private static final String KEY = "JDJhJDEwJHNrbDRkNFkyTVI2Yy5uWmhWM3VWSy5HQmVLZDNNTDRSS3lNbnM4RFpxajkxSGpmL0hZcmNT";
	public static final String API_HOST = "api.curseforge.com";
	public static final String CDN_HOST = "edge.forgecdn.net";
	public static final String BASE_URL = "https://" + API_HOST + "/v1";

	// key - sha1, value - murmur
	// https://docs.curseforge.com/?java#get-fingerprints-matches
	public static List<CurseForgeAPI> getModInfosFromFingerPrints(Map<String, String> hashes) {
		if (hashes == null || hashes.isEmpty()) return null;

		String requestUrl = BASE_URL + "/fingerprints";
		List<CurseForgeAPI> curseForgeAPIList = new LinkedList<>();

		try {
			JsonArray exactMatches = fromCurseForgeUrl(requestUrl, hashes.values().stream().toList()).get("data").getAsJsonObject().get("exactMatches")
					.getAsJsonArray();
			for (JsonElement match : exactMatches) {
				JsonObject JSONObject = match.getAsJsonObject();
				CurseForgeAPI curseForgeAPI = parseJsonObject(JSONObject, hashes);
				if (curseForgeAPI != null) curseForgeAPIList.add(curseForgeAPI);
			}
			Map<Integer, String> projectPageUrls = getProjectPageUrls(curseForgeAPIList);
			for (int index = 0; index < curseForgeAPIList.size(); index++) {
				CurseForgeAPI info = curseForgeAPIList.get(index);
				curseForgeAPIList.set(index, info.withProjectPageUrl(projectPageUrls.get(info.modId())));
			}
		} catch (Exception e) {
			LOGGER.error("Failed to fetch data from CurseForge API", e);
		}

		return curseForgeAPIList;
	}

	private static CurseForgeAPI parseJsonObject(JsonObject JSONObject, Map<String, String> hashes) {
		if (JSONObject == null) {
			LOGGER.error("CurseForgeAPI Can't parse null object");
			return null;
		}

		JsonObject fileJson = JSONObject.get("file").getAsJsonObject();

		// https://docs.curseforge.com/?java#tocS_FileReleaseType
		int releaseTypeInt = fileJson.get("releaseType").getAsInt();
		String releaseType = switch (releaseTypeInt) {
			case 1 -> "release";
			case 2 -> "beta";
			case 3 -> "alpha";
			default -> null;
		};

		JsonArray fileHashes = fileJson.getAsJsonArray("hashes");

		String sha1 = null;
		boolean found = false;

		for (JsonElement hashElement : fileHashes) {
			JsonObject hashObject = hashElement.getAsJsonObject();
			// sha1 - https://docs.curseforge.com/?java#tocS_FileHash
			if (hashObject.get("algo").getAsInt() == 1) {
				var hash = hashObject.get("value").getAsString();
				if (hashes.containsKey(hash)) {
					sha1 = hash;
					found = true;
					break;
				}
			}
		}

		if (!found) {
			LOGGER.error("CurseForgeAPI Can't find file with SHA1 hash: {}", sha1);
			return null;
		}

		// Download url may be null if mod author dont allow it
		String downloadUrl = fileJson.get("downloadUrl").isJsonNull() ? null : fileJson.get("downloadUrl").getAsString();
		if (downloadUrl == null) return null;
		String fileName = fileJson.get("fileName").getAsString();
		String fileVersion = fileJson.get("displayName").getAsString();
		String fileSize = String.valueOf(fileJson.get("fileLength").getAsLong());
		String murmur = hashes.get(sha1);
		int modId = fileJson.has("modId") && !fileJson.get("modId").isJsonNull() ? fileJson.get("modId").getAsInt() : 0;

		return new CurseForgeAPI(null, downloadUrl, fileVersion, fileName, fileSize, releaseType, murmur, sha1, modId, null);
	}

	private static Map<Integer, String> getProjectPageUrls(List<CurseForgeAPI> infos) throws IOException {
		List<Integer> modIds = infos.stream().map(CurseForgeAPI::modId).filter(id -> id > 0).distinct().toList();
		if (modIds.isEmpty()) return Map.of();
		JsonObject request = new JsonObject();
		request.add("modIds", new Gson().toJsonTree(modIds));
		JsonObject response = fromCurseForgeUrl(BASE_URL + "/mods", request);
		if (response == null || !response.has("data") || !response.get("data").isJsonArray()) return Map.of();
		Map<Integer, String> urls = new HashMap<>();
		for (JsonElement element : response.getAsJsonArray("data")) {
			JsonObject project = element.getAsJsonObject();
			if (!project.has("id")) continue;
			String pageUrl = null;
			if (project.has("links") && project.get("links").isJsonObject()) {
				JsonElement website = project.getAsJsonObject("links").get("websiteUrl");
				if (website != null && !website.isJsonNull() && !website.getAsString().isBlank()) pageUrl = website.getAsString();
			}
			if (pageUrl == null && project.has("slug") && !project.get("slug").isJsonNull() && !project.get("slug").getAsString().isBlank())
				pageUrl = "https://www.curseforge.com/minecraft/mc-mods/" + project.get("slug").getAsString();
			if (pageUrl != null) urls.put(project.get("id").getAsInt(), pageUrl);
		}
		return urls;
	}

	private CurseForgeAPI withProjectPageUrl(String url) {
		return new CurseForgeAPI(requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, murmurHash, sha1Hash, modId, url);
	}

	private static JsonObject fromCurseForgeUrl(String requestUrl, List<String> murmurHashes) throws IOException {
		if (murmurHashes == null || murmurHashes.isEmpty()) return null;
		JsonObject request = new JsonObject();
		request.add("fingerprints", new Gson().toJsonTree(murmurHashes));
		return fromCurseForgeUrl(requestUrl, request);
	}

	private static JsonObject fromCurseForgeUrl(String requestUrl, JsonObject requestBody) throws IOException {
		if (requestBody == null) return null;
		URL url = new URL(requestUrl);
		if (!"https".equalsIgnoreCase(url.getProtocol()) || !API_HOST.equalsIgnoreCase(url.getHost()) || url.getUserInfo() != null
				|| (url.getPort() != -1 && url.getPort() != 443))
			throw new IOException("Refusing to send the CurseForge API key to an untrusted endpoint");
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setInstanceFollowRedirects(false);
		connection.setRequestProperty("User-Agent", NetUtils.USER_AGENT);
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty("x-api-key", summonKey());
		connection.setConnectTimeout(NetUtils.HTTP_TIMEOUT_MILLIS);
		connection.setReadTimeout(NetUtils.HTTP_TIMEOUT_MILLIS);
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.getOutputStream().write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
		connection.connect();
		JsonElement element = null;
		int code = connection.getResponseCode();
		if (code == HttpURLConnection.HTTP_OK) {
			try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
				element = new JsonParser().parse(reader);
			}
		} else if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
			LOGGER.error("CurseForge API authorization failed with HTTP 401");
		} else {
			LOGGER.warn("{} responded {} code", url, code);
		}
		connection.disconnect();
		if (element != null && !element.isJsonArray()) return element.getAsJsonObject();
		return null;
	}

	public static String summonKey() {
		return new String(Base64.getDecoder().decode(KEY), StandardCharsets.UTF_8);
	}

}
