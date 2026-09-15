package pl.skidam.automodpack_core.platforms;

import static pl.skidam.automodpack_core.Constants.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.utils.Json;

public record ModrinthAPI(String modrinthID, String requestUrl, String downloadUrl, String fileVersion, String fileName, long fileSize, String releaseType,
		String SHA1Hash) {

	private static final String BASE_URL = "https://api.modrinth.com/v2";
	private static final Set<String> DOWNLOADABLE_VERSION_STATUSES = Set.of("listed", "archived", "unlisted");
	private static final Set<String> DOWNLOADABLE_PROJECT_STATUSES = Set.of("approved", "archived", "unlisted");

	public static List<ModrinthAPI> getModInfosFromID(String modrinthID) {
		if (modrinthID == null) return null;

		if (modrinthID.isBlank()) return null;

		String modLoader = LOADER_MANAGER.getPlatformType().toString().toLowerCase(Locale.ROOT);
		String requestUrl = BASE_URL + "/project/" + modrinthID + "/version?loaders=[\"" + modLoader + "\"]&game_versions=[\"" + MC_VERSION + "\"]";
		requestUrl = requestUrl.replaceAll("\"", "%22"); // so important!

		List<ModrinthAPI> modrinthAPIList = new ArrayList<>();

		try {
			JsonArray JSONArray = Json.fromUrlAsArray(requestUrl);

			if (JSONArray == null) {
				LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
				return null;
			}

			for (JsonElement jsonElement : JSONArray) {
				JsonObject JSONObject = jsonElement.getAsJsonObject();

				String fileVersion = JSONObject.get("version_number").getAsString();
				String releaseType = JSONObject.get("version_type").getAsString();

				JsonObject JSONObjectFiles = JSONObject.getAsJsonArray("files").get(0).getAsJsonObject();

				String downloadUrl = JSONObjectFiles.get("url").getAsString();
				String fileName = JSONObjectFiles.get("filename").getAsString();
				long fileSize = JSONObjectFiles.get("size").getAsLong();
				String SHA1Hash = JSONObjectFiles.get("hashes").getAsJsonObject().get("sha1").getAsString();

				modrinthAPIList.add(new ModrinthAPI(modrinthID, requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, SHA1Hash));
			}
		} catch (IndexOutOfBoundsException e) {
			LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return modrinthAPIList;
	}

	public static ModrinthAPI getModSpecificVersion(String modrinthID, String modVersion, String mcVersion) {
		if (modrinthID == null || modVersion == null || mcVersion == null) return null;

		if (modrinthID.isBlank() || modVersion.isBlank() || mcVersion.isBlank()) return null;

		String modLoader = LOADER_MANAGER.getPlatformType().toString().toLowerCase(Locale.ROOT);
		String requestUrl = BASE_URL + "/project/" + modrinthID + "/version?loaders=[\"" + modLoader + "\"]&game_versions=[\"" + mcVersion + "\"]";
		requestUrl = requestUrl.replaceAll("\"", "%22"); // important!

		try {
			JsonArray JSONArray = Json.fromUrlAsArray(requestUrl);

			if (JSONArray == null) {
				LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
				return null;
			}

			for (JsonElement jsonElement : JSONArray) {
				JsonObject JSONObject = jsonElement.getAsJsonObject();

				String fileVersion = JSONObject.get("version_number").getAsString();

				if (fileVersion.equals(modVersion)) {
					String releaseType = JSONObject.get("version_type").getAsString();

					JsonObject JSONObjectFiles = JSONObject.getAsJsonArray("files").get(0).getAsJsonObject();

					String downloadUrl = JSONObjectFiles.get("url").getAsString();
					String fileName = JSONObjectFiles.get("filename").getAsString();
					long fileSize = JSONObjectFiles.get("size").getAsLong();
					String SHA1Hash = JSONObjectFiles.get("hashes").getAsJsonObject().get("sha1").getAsString();

					return new ModrinthAPI(modrinthID, requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, SHA1Hash);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;

	}

	// https://docs.modrinth.com/#tag/version-files/operation/versionsFromHashes
	public static List<ModrinthAPI> getModsInfosFromListOfSHA1(List<String> listOfSha1) {
		if (listOfSha1 == null || listOfSha1.isEmpty()) return null;

		String requestUrl = BASE_URL + "/version_files";
		List<ModrinthAPI> modrinthAPIList = new LinkedList<>();

		try {
			JsonObject JSONObjects = Json.fromModrinthUrl(requestUrl, listOfSha1);
			Set<String> wantedSha1s = Set.copyOf(listOfSha1);
			for (String key : JSONObjects.keySet()) {
				JsonObject JSONObject = JSONObjects.getAsJsonObject(key);
				ModrinthAPI modrinthAPI = parseJsonObject(JSONObject, wantedSha1s);
				if (modrinthAPI != null) modrinthAPIList.add(modrinthAPI);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to fetch data from Modrinth API", e);
		}

		return modrinthAPIList;
	}

	private static ModrinthAPI parseJsonObject(JsonObject JSONObject, Set<String> wantedSha1s) {
		if (JSONObject == null || !isDownloadableVersion(JSONObject)) return null;

		String modrinthID = JSONObject.get("project_id").getAsString();
		String fileVersion = JSONObject.get("version_number").getAsString();
		String releaseType = JSONObject.get("version_type").getAsString();

		JsonArray filesArray = JSONObject.getAsJsonArray("files");
		JsonObject JSONObjectFile = null;

		String sha1 = wantedSha1s.size() == 1 ? wantedSha1s.iterator().next() : null;

		// some projects can have more than one file under the same version
		for (JsonElement fileElement : filesArray) {
			JsonObject fileObject = fileElement.getAsJsonObject();
			JsonObject hashesObject = fileObject.getAsJsonObject("hashes");
			String sha1Hash = hashesObject.get("sha1").getAsString();

			if (sha1 != null && sha1.equals(sha1Hash)) {
				JSONObjectFile = fileObject;
				break;
			} else if (wantedSha1s.contains(sha1Hash)) {
				JSONObjectFile = fileObject;
				break;
			}
		}

		if (JSONObjectFile == null) {
			if (sha1 != null) LOGGER.error("Can't find file with SHA1 hash: " + sha1);
			return null;
		}

		String downloadUrl = JSONObjectFile.get("url").getAsString();
		String fileName = JSONObjectFile.get("filename").getAsString();
		long fileSize = JSONObjectFile.get("size").getAsLong();
		if (sha1 == null) sha1 = JSONObjectFile.get("hashes").getAsJsonObject().get("sha1").getAsString();

		return new ModrinthAPI(modrinthID, null, downloadUrl, fileVersion, fileName, fileSize, releaseType, sha1);
	}

	/** The human project slug of every given project id that is publicly downloadable; ids without a slug or a downloadable status are absent. */
	public static Map<String, String> getProjectSlugs(Collection<String> projectIds) {
		if (projectIds == null || projectIds.isEmpty()) return Map.of();
		String requestUrl = BASE_URL + "/projects?ids=" + projectIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
		requestUrl = requestUrl.replaceAll("\"", "%22"); // so important!

		Map<String, String> slugs = new LinkedHashMap<>();
		try {
			JsonArray projects = Json.fromUrlAsArray(requestUrl);
			if (projects == null) return Map.of();
			for (JsonElement element : projects) {
				JsonObject project = element.getAsJsonObject();
				if (!isDownloadableProject(project) || !project.has("id") || !project.has("slug") || project.get("slug").isJsonNull()) continue;
				String slug = project.get("slug").getAsString();
				if (slug.isBlank()) continue;
				slugs.put(project.get("id").getAsString(), slug);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to fetch project slugs from Modrinth API", e);
		}
		return slugs;
	}

	/** Modrinth has no CurseForge `isAvailable`; a missing or non-downloadable version status is not a public hit. */
	public static boolean isDownloadableVersion(JsonObject version) {
		return hasDownloadableStatus(version, DOWNLOADABLE_VERSION_STATUSES);
	}

	/** Approved, archived, and unlisted projects still serve files; draft/private/rejected/withheld do not. */
	public static boolean isDownloadableProject(JsonObject project) {
		return hasDownloadableStatus(project, DOWNLOADABLE_PROJECT_STATUSES);
	}

	private static boolean hasDownloadableStatus(JsonObject object, Set<String> allowed) {
		if (object == null || !object.has("status") || object.get("status").isJsonNull()) return false;
		String status = object.get("status").getAsString();
		return status != null && allowed.contains(status.toLowerCase(Locale.ROOT));
	}

	/** The project page of one file type and project slug. */
	public static String getMainPageUrl(String fileType, String projectSlug) {
		return "https://modrinth.com/" + fileType + "/" + projectSlug;
	}
}
