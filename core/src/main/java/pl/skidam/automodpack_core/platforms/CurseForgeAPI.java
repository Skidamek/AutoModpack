package pl.skidam.automodpack_core.platforms;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.http.HttpResponse;
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

import pl.skidam.automodpack_core.utils.HttpClientPool;

@SuppressWarnings("deprecation")
public record CurseForgeAPI(String requestUrl, String downloadUrl, String fileVersion, String fileName, String fileSize, String releaseType, String murmurHash,
		String sha1Hash, int modId, String projectPageUrl) {

	private static final String KEY = "JDJhJDEwJHNrbDRkNFkyTVI2Yy5uWmhWM3VWSy5HQmVLZDNNTDRSS3lNbnM4RFpxajkxSGpmL0hZcmNT";
	public static final String API_HOST = "api.curseforge.com";
	public static final String CDN_HOST = "edge.forgecdn.net";
	public static final String BASE_URL = "https://" + API_HOST + "/v1";

	/** The one endpoint the API key is provisioned for; the key never travels anywhere else. */
	public record TrustedEndpoint(String scheme, String host, int port) {
		public static final TrustedEndpoint PRODUCTION = new TrustedEndpoint("https", API_HOST, 443);

		public void refuseForeign(String requestUrl) throws IOException {
			URL url = new URL(requestUrl);
			if (!scheme.equalsIgnoreCase(url.getProtocol()) || !host.equalsIgnoreCase(url.getHost()) || url.getUserInfo() != null
					|| (url.getPort() != -1 && url.getPort() != port))
				throw new IOException("Refusing to send the CurseForge API key to an untrusted endpoint");
		}
	}

	// key - sha1, value - murmur
	// https://docs.curseforge.com/?java#get-fingerprints-matches
	public static List<CurseForgeAPI> getModInfosFromFingerPrints(Map<String, String> hashes) {
		return getModInfosFromFingerPrints(BASE_URL, TrustedEndpoint.PRODUCTION, hashes);
	}

	/** The request base url and the endpoint the key is provisioned for travel together, so the pin always holds; tests point both at a local server. */
	public static List<CurseForgeAPI> getModInfosFromFingerPrints(String baseUrl, TrustedEndpoint endpoint, Map<String, String> hashes) {
		if (hashes == null || hashes.isEmpty()) return null;

		List<CurseForgeAPI> curseForgeAPIList = new LinkedList<>();

		try {
			JsonArray exactMatches = fromCurseForgeUrl(baseUrl + "/fingerprints", endpoint, murmurRequest(hashes.values().stream().toList())).get("data")
					.getAsJsonObject().get("exactMatches").getAsJsonArray();
			for (JsonElement match : exactMatches) {
				JsonObject JSONObject = match.getAsJsonObject();
				CurseForgeAPI curseForgeAPI = parseJsonObject(JSONObject, hashes);
				if (curseForgeAPI != null) curseForgeAPIList.add(curseForgeAPI);
			}
			Map<Integer, String> listedPages = getListedProjectPages(curseForgeAPIList, baseUrl, endpoint);
			if (listedPages != null) {
				List<CurseForgeAPI> listed = new LinkedList<>();
				for (CurseForgeAPI info : curseForgeAPIList) {
					if (!listedPages.containsKey(info.modId())) continue;
					listed.add(info.withProjectPageUrl(listedPages.get(info.modId())));
				}
				curseForgeAPIList = listed;
			}
		} catch (Exception e) {
			LOGGER.error("Failed to fetch data from CurseForge API", e);
		}

		return curseForgeAPIList;
	}

	/** CurseForge assigns `project-{id}` as the slug/name/page of unlisted or deleted projects. */
	public static boolean isPlaceholderSlug(String slug) {
		if (slug == null || slug.length() < 9 || !slug.regionMatches(true, 0, "project-", 0, 8)) return false;
		for (int i = 8; i < slug.length(); i++) {
			char character = slug.charAt(i);
			if (character < '0' || character > '9') return false;
		}
		return true;
	}

	public static boolean isPlaceholderProjectPage(String url) {
		if (url == null || url.isBlank()) return false;
		int end = url.length();
		while (end > 0 && url.charAt(end - 1) == '/') end--;
		int start = url.lastIndexOf('/', end - 1) + 1;
		return start > 0 && start < end && isPlaceholderSlug(url.substring(start, end));
	}

	public static boolean isListedProject(JsonObject project) {
		if (project == null || !project.has("isAvailable") || project.get("isAvailable").isJsonNull()) return false;
		return project.get("isAvailable").getAsBoolean();
	}

	public static String publicProjectPageUrl(String websiteUrl, String slug) {
		if (websiteUrl != null && !websiteUrl.isBlank() && !isPlaceholderProjectPage(websiteUrl)) return websiteUrl;
		if (slug != null && !slug.isBlank() && !isPlaceholderSlug(slug)) return "https://www.curseforge.com/minecraft/mc-mods/" + slug;
		return null;
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

	/** Listed projects keyed by mod id; the page url may be null. Null means the bulk lookup failed and fingerprint hits should be kept. */
	private static Map<Integer, String> getListedProjectPages(List<CurseForgeAPI> infos, String baseUrl, TrustedEndpoint endpoint) throws IOException {
		List<Integer> modIds = infos.stream().map(CurseForgeAPI::modId).filter(id -> id > 0).distinct().toList();
		if (modIds.isEmpty()) return new HashMap<>();
		JsonObject request = new JsonObject();
		request.add("modIds", new Gson().toJsonTree(modIds));
		JsonObject response = fromCurseForgeUrl(baseUrl + "/mods", endpoint, request);
		if (response == null || !response.has("data") || !response.get("data").isJsonArray()) return null;
		Map<Integer, String> listed = new HashMap<>();
		for (JsonElement element : response.getAsJsonArray("data")) {
			JsonObject project = element.getAsJsonObject();
			if (!project.has("id") || !isListedProject(project)) continue;
			String websiteUrl = null;
			if (project.has("links") && project.get("links").isJsonObject()) {
				JsonElement website = project.getAsJsonObject("links").get("websiteUrl");
				if (website != null && !website.isJsonNull() && !website.getAsString().isBlank()) websiteUrl = website.getAsString();
			}
			String slug = project.has("slug") && !project.get("slug").isJsonNull() ? project.get("slug").getAsString() : null;
			listed.put(project.get("id").getAsInt(), publicProjectPageUrl(websiteUrl, slug));
		}
		return listed;
	}

	private CurseForgeAPI withProjectPageUrl(String url) {
		return new CurseForgeAPI(requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, murmurHash, sha1Hash, modId, url);
	}

	private static JsonObject murmurRequest(List<String> murmurHashes) {
		JsonObject request = new JsonObject();
		request.add("fingerprints", new Gson().toJsonTree(murmurHashes));
		return request;
	}

	private static JsonObject fromCurseForgeUrl(String requestUrl, TrustedEndpoint endpoint, JsonObject requestBody) throws IOException {
		if (requestBody == null) return null;
		endpoint.refuseForeign(requestUrl);
		Map<String, String> headers = Map.of("Content-Type", "application/json", "Accept", "application/json", "x-api-key", summonKey());
		byte[] body = requestBody.toString().getBytes(StandardCharsets.UTF_8);
		// The key must never see a redirect target, so this request never follows one.
		HttpResponse<byte[]> response = HttpClientPool.request(requestUrl, headers, body, false);
		int code = response.statusCode();
		if (code == 200) {
			return parseObject(response.body());
		}
		if (code == 401) LOGGER.error("CurseForge API authorization failed with HTTP 401");
		else LOGGER.warn("{} responded {} code", requestUrl, code);
		return null;
	}

	/** The body parsed as a JSON object; an array body is not an answer. */
	private static JsonObject parseObject(byte[] body) {
		JsonElement element = new JsonParser().parse(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8)); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
		if (!element.isJsonArray()) return element.getAsJsonObject();
		return null;
	}

	public static String summonKey() {
		return new String(Base64.getDecoder().decode(KEY), StandardCharsets.UTF_8);
	}

}
