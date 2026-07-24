package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public class ModpackContent {
	public final Set<Jsons.ModpackContentFields.ModpackContentItem> list = ConcurrentHashMap.newKeySet();
	public final ObservableMap<String, Path> pathsMap = new ObservableMap<>();
	private final String MODPACK_ID;
	private final String MODPACK_NAME;
	private final Map<String, GroupScanners> GROUP_SCANNERS = new LinkedHashMap<>();
	private final Map<String, Jsons.ModpackContentFields.ModpackGroupFields> GROUP_FIELDS = new LinkedHashMap<>();
	private final String FALLBACK_GROUP_ID;
	private final Path MODPACK_DIR;
	private final ThreadPoolExecutor CREATION_EXECUTOR;
	private final Map<String, String> sha1MurmurMapPreviousContent = new HashMap<>();
	private Optional<Jsons.ModpackContentFields> cachedPreviousContent;

	// The four file-rule scanners belonging to one group.
	private record GroupScanners(FileTreeScanner synced, FileTreeScanner editable, FileTreeScanner overwriteEditable, FileTreeScanner forceCopy) {
		void scanAll() {
			synced.scan();
			editable.scan();
			overwriteEditable.scan();
			forceCopy.scan();
		}
	}

	/**
	 * Pre-groups behaviour: every file belongs to a single implicitly-required group.
	 */
	public ModpackContent(String modpackName, Path cwd, Path modpackDir, Set<String> syncedFiles, Set<String> allowEditsInFiles,
			Set<String> overwriteEditableFiles, Set<String> forceCopyFilesToStandardLocation, ThreadPoolExecutor CREATION_EXECUTOR) {
		this(modpackName, cwd, modpackDir, singleGroup(syncedFiles, allowEditsInFiles, overwriteEditableFiles, forceCopyFilesToStandardLocation),
				CREATION_EXECUTOR);
	}

	public ModpackContent(String modpackName, Path cwd, Path modpackDir, Map<String, Jsons.GroupDeclaration> groups,
			ThreadPoolExecutor CREATION_EXECUTOR) {
		this.MODPACK_NAME = modpackName;
		this.MODPACK_DIR = modpackDir;
		this.cachedPreviousContent = getPreviousContent();
		this.MODPACK_ID = resolveModpackId(cachedPreviousContent);
		this.CREATION_EXECUTOR = CREATION_EXECUTOR;

		Set<Path> directoriesToSearch = new HashSet<>(2);
		if (MODPACK_DIR != null) directoriesToSearch.add(MODPACK_DIR);
		if (cwd != null) directoriesToSearch.add(cwd);
		Set<Path> syncedSearchDirectories = cwd != null ? Set.of(cwd) : Set.of();

		Map<String, Jsons.GroupDeclaration> declarations = groups == null || groups.isEmpty()
				? Map.of("main", Jsons.mainGroupDeclaration())
				: groups;

		for (var entry : declarations.entrySet()) {
			Jsons.GroupDeclaration declaration = entry.getValue();
			if (declaration == null) continue;
			GROUP_SCANNERS.put(entry.getKey(), new GroupScanners(new FileTreeScanner(declaration.syncedFiles, syncedSearchDirectories),
					new FileTreeScanner(declaration.allowEditsInFiles, directoriesToSearch),
					new FileTreeScanner(declaration.overwriteEditableFiles, directoriesToSearch),
					new FileTreeScanner(declaration.forceCopyFilesToStandardLocation, directoriesToSearch)));
			GROUP_FIELDS.put(entry.getKey(), new Jsons.ModpackContentFields.ModpackGroupFields(declaration));
		}

		this.FALLBACK_GROUP_ID = resolveFallbackGroupId(declarations);
	}

	private static Map<String, Jsons.GroupDeclaration> singleGroup(Set<String> syncedFiles, Set<String> allowEditsInFiles,
			Set<String> overwriteEditableFiles, Set<String> forceCopyFilesToStandardLocation) {
		Jsons.GroupDeclaration declaration = new Jsons.GroupDeclaration();
		declaration.displayName = "Main";
		declaration.required = true;
		declaration.recommended = true;
		declaration.syncedFiles = syncedFiles == null ? Set.of() : syncedFiles;
		declaration.allowEditsInFiles = allowEditsInFiles == null ? Set.of() : allowEditsInFiles;
		declaration.overwriteEditableFiles = overwriteEditableFiles == null ? Set.of() : overwriteEditableFiles;
		declaration.forceCopyFilesToStandardLocation = forceCopyFilesToStandardLocation == null ? Set.of() : forceCopyFilesToStandardLocation;
		return Map.of("main", declaration);
	}

	// Files found under the modpack dir that no group's globs claim have to land somewhere; a
	// required group is the only safe home, otherwise the player could deselect them away.
	private static String resolveFallbackGroupId(Map<String, Jsons.GroupDeclaration> declarations) {
		return declarations.entrySet().stream().filter(entry -> entry.getValue() != null && entry.getValue().required).map(Map.Entry::getKey).findFirst()
				.orElseGet(() -> declarations.keySet().stream().findFirst().orElse("main"));
	}

	private String resolveModpackId(Optional<Jsons.ModpackContentFields> previousContent) {
		if (previousContent.isEmpty() || previousContent.get().modpackId == null || previousContent.get().modpackId.isBlank()) return ModpackId.generate();
		return ModpackId.requireValid(previousContent.get().modpackId);
	}

	private Optional<Jsons.ModpackContentFields> consumePreviousContent() {
		if (cachedPreviousContent == null) return getPreviousContent();
		var previousContent = cachedPreviousContent;
		cachedPreviousContent = null;
		return previousContent;
	}

	public String getModpackId() {
		return MODPACK_ID;
	}

	public String getModpackName() {
		return MODPACK_NAME;
	}

	public boolean create(FileMetadataCache cache) {
		Set<Jsons.ModpackContentFields.FileToDelete> computedFilesToDelete = new HashSet<>();

		try {
			GROUP_SCANNERS.values().forEach(GroupScanners::scanAll);

			pathsMap.clear();
			sha1MurmurMapPreviousContent.clear();
			GROUP_FIELDS.values().forEach(group -> group.files = ConcurrentHashMap.newKeySet());

			consumePreviousContent().ifPresent(previousContent -> {
				Map<String, Jsons.ModpackContentFields.FileToDelete> oldFilesMap = previousContent.nonModpackFilesToDelete.stream()
						.collect(Collectors.toMap(f -> f.file, f -> f, (a, b) -> a));

				if (serverConfig != null && serverConfig.nonModpackFilesToDelete != null) {
					for (var fileToDeleteEntry : serverConfig.nonModpackFilesToDelete.entrySet()) {
						var file = fileToDeleteEntry.getKey();
						var sha1 = fileToDeleteEntry.getValue();
						if (oldFilesMap.containsKey(file) && oldFilesMap.get(file).sha1.equalsIgnoreCase(sha1)) {
							computedFilesToDelete.add(oldFilesMap.get(file));
						} else {
							String currentTimestamp = String.valueOf(System.currentTimeMillis());
							computedFilesToDelete.add(new Jsons.ModpackContentFields.FileToDelete(file, sha1, currentTimestamp));
						}
					}
				}

				previousContent.list.forEach(item -> sha1MurmurMapPreviousContent.put(item.sha1, item.murmur));
			});

			Map<String, Path> filesToProcess = new HashMap<>();

			for (var groupEntry : GROUP_SCANNERS.entrySet()) {
				groupEntry.getValue().synced().getMatchedPaths().values()
						.forEach(path -> filesToProcess.put(SmartFileUtils.formatPath(path, MODPACK_DIR), path));
			}

			if (MODPACK_DIR != null) {
				try (Stream<Path> stream = Files.walk(MODPACK_DIR)) { // in case there any files with the same relative path, we prefer from MODPACK_DIR, this
																		// will override previous entries
					stream.forEach(path -> filesToProcess.put(SmartFileUtils.formatPath(path, MODPACK_DIR), path));
				}
			}

			var tempPathMap = new ConcurrentHashMap<>(filesToProcess);

			List<CompletableFuture<GroupedItem>> futures = filesToProcess.entrySet().stream()
					.map(entry -> CompletableFuture.supplyAsync(() -> {
						try {
							String groupId = resolveGroupId(entry.getKey());
							var contentEntry = generateContent(entry.getValue(), entry.getKey(), cache, GROUP_SCANNERS.get(groupId));
							if (contentEntry == null) return null;
							LOGGER.debug("Generated modpack content for {} in group {}", entry.getValue(), groupId);
							tempPathMap.put(contentEntry.sha1, entry.getValue());
							return new GroupedItem(groupId, contentEntry);
						} catch (Exception e) {
							LOGGER.error("Error generating content for {}", entry.getValue(), e);
							return null;
						}
					}, CREATION_EXECUTOR)).toList();

			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

			for (var future : futures) {
				GroupedItem grouped = future.join();
				if (grouped != null) {
					list.add(grouped.item());
					pathsMap.put(grouped.item().sha1, tempPathMap.get(grouped.item().sha1));
					var group = GROUP_FIELDS.get(grouped.groupId());
					if (group != null) group.files.add(grouped.item().file);
				}
			}

			if (list.isEmpty()) {
				LOGGER.warn("Modpack is empty!");
				return false;
			} else {
				LOGGER.info("Modpack generated with {} files!", list.size());
			}
		} catch (Exception e) {
			LOGGER.error("Error while generating modpack!", e);
			return false;
		}

		saveModpackContent(computedFilesToDelete);
		if (hostServer != null) hostServer.setPaths(pathsMap);

		return true;
	}

	public Optional<Jsons.ModpackContentFields> getPreviousContent() {
		var optionalModpackContentFile = ModpackContentTools.getModpackContentFile(MODPACK_DIR);
		return optionalModpackContentFile.map(ModpackContentTools::read);
	}

	public boolean loadPreviousContent() {
		var optionalPreviousModpackContent = consumePreviousContent();
		if (optionalPreviousModpackContent.isEmpty()) return false;
		Jsons.ModpackContentFields previousModpackContent = optionalPreviousModpackContent.get();

		synchronized (list) {
			list.addAll(previousModpackContent.list);

			// Reuse the recorded membership; without a rescan we cannot recompute it here.
			if (previousModpackContent.groups != null) {
				previousModpackContent.groups.forEach((groupId, previousGroup) -> {
					var group = GROUP_FIELDS.get(groupId);
					if (group != null && previousGroup.files != null) group.files.addAll(previousGroup.files);
				});
			}

			for (Jsons.ModpackContentFields.ModpackContentItem modpackContentItem : list) {
				Path file = SmartFileUtils.getPath(MODPACK_DIR, modpackContentItem.file);
				if (!Files.exists(file)) file = SmartFileUtils.getPathFromCWD(modpackContentItem.file);
				if (!Files.exists(file)) {
					LOGGER.warn("File {} does not exist!", file);
					continue;
				}

				pathsMap.put(modpackContentItem.sha1, file);
			}
		}

		if (hostServer != null) hostServer.setPaths(pathsMap);

		saveModpackContent(previousModpackContent.nonModpackFilesToDelete);

		return true;
	}

	public synchronized void saveModpackContent(Set<Jsons.ModpackContentFields.FileToDelete> nonModpackFilesToDelete) {
		if (nonModpackFilesToDelete == null) throw new IllegalArgumentException("filesToDelete is null");

		synchronized (list) {
			Jsons.ModpackContentFields modpackContent = new Jsons.ModpackContentFields(list);

			modpackContent.automodpackVersion = AM_VERSION;
			modpackContent.mcVersion = MC_VERSION;
			modpackContent.loaderVersion = LOADER_VERSION;
			modpackContent.loader = LOADER;
			modpackContent.modpackId = MODPACK_ID;
			modpackContent.modpackName = MODPACK_NAME;
			modpackContent.nonModpackFilesToDelete = nonModpackFilesToDelete;
			modpackContent.groups = new LinkedHashMap<>(GROUP_FIELDS);

			try {
				ModpackContentTools.write(hostModpackContentFile, modpackContent);
			} catch (IOException e) {
				throw new ConfigTools.ConfigException("Failed to save modpack content", e);
			}
		}
	}

	public CompletableFuture<Void> replaceAsync(Path file, FileMetadataCache cache) {
		return CompletableFuture.runAsync(() -> replace(file, cache), CREATION_EXECUTOR);
	}

	public void replace(Path file, FileMetadataCache cache) {
		remove(file);
		try {
			String modpackFile = SmartFileUtils.formatPath(file, MODPACK_DIR);
			String groupId = resolveGroupId(modpackFile);
			Jsons.ModpackContentFields.ModpackContentItem item = generateContent(file, modpackFile, cache, GROUP_SCANNERS.get(groupId));
			if (item != null) {
				LOGGER.info("generated content for {}", item.file);
				synchronized (list) {
					list.add(item);
				}
				pathsMap.put(item.sha1, file);
				var group = GROUP_FIELDS.get(groupId);
				if (group != null) group.files.add(item.file);
			}
		} catch (Exception e) {
			LOGGER.error("Error while replacing content for: " + file, e);
		}
	}

	public void remove(Path file) {
		String modpackFile = SmartFileUtils.formatPath(file, MODPACK_DIR);

		synchronized (list) {
			for (Jsons.ModpackContentFields.ModpackContentItem item : this.list) {
				if (item.file.equals(modpackFile)) {
					this.pathsMap.remove(item.sha1);
					this.list.remove(item);
					GROUP_FIELDS.values().forEach(group -> group.files.remove(item.file));
					LOGGER.info("Removed content for {}", modpackFile);
					break;
				}
			}
		}
	}

	public static boolean isInnerFile(Path file) {
		Path normalizedFilePath = file.toAbsolutePath().normalize();
		boolean isInner = normalizedFilePath.startsWith(automodpackDir.toAbsolutePath().normalize())
				&& !normalizedFilePath.startsWith(hostModpackDir.toAbsolutePath().normalize());
		if (!isInner && normalizedFilePath.equals(hostModpackContentFile.toAbsolutePath().normalize())) { // special case, since its inside hostModpackDir
			return true;
		}

		return isInner;
	}

	private record GroupedItem(String groupId, Jsons.ModpackContentFields.ModpackContentItem item) {}

	/**
	 * First group whose synced globs claim this path wins; declaration order in the config decides ties.
	 */
	private String resolveGroupId(String formattedFile) {
		for (var entry : GROUP_SCANNERS.entrySet()) {
			if (entry.getValue().synced().matches(formattedFile)) return entry.getKey();
		}
		return FALLBACK_GROUP_ID;
	}

	public Map<String, Jsons.ModpackContentFields.ModpackGroupFields> getGroups() {
		return GROUP_FIELDS;
	}

	private Jsons.ModpackContentFields.ModpackContentItem generateContent(final Path file, final String formattedFile, FileMetadataCache cache,
			GroupScanners scanners) throws Exception {
		if (!Files.isRegularFile(file)) return null;

		if (serverConfig == null) {
			LOGGER.error("Server config is null!");
			return null;
		}

		if (isInnerFile(file)) return null;

		if (formattedFile.startsWith("/automodpack/")) return null;

		final String size = String.valueOf(Files.size(file));

		if (serverConfig.autoExcludeUnnecessaryFiles) {
			if (size.equals("0")) {
				LOGGER.info("Skipping file {} because it is empty", formattedFile);
				return null;
			}

			if (file.getFileName().toString().startsWith(".")) {
				LOGGER.info("Skipping file {} is hidden", formattedFile);
				return null;
			}

			if (formattedFile.endsWith(".tmp")) {
				LOGGER.info("File {} is temporary! Skipping...", formattedFile);
				return null;
			}

			if (formattedFile.endsWith(".disabled")) {
				LOGGER.info("File {} is disabled! Skipping...", formattedFile);
				return null;
			}

			if (formattedFile.endsWith(".bak")) {
				LOGGER.info("File {} is backup file, unnecessary on client! Skipping...", formattedFile);
				return null;
			}
		}

		String type;

		if (FileInspection.isMod(file)) {
			type = "mod";
			if (serverConfig.autoExcludeServerSideMods && Objects.equals(FileInspection.getModEnvironment(file), LoaderManagerService.EnvironmentType.SERVER)) {
				LOGGER.info("File {} is server mod! Skipping...", formattedFile);
				return null;
			}
			// Exclude AutoModpack itself
			var modId = FileInspection.getModID(file);
			if ((MOD_ID + "_bootstrap").equals(modId) || (MOD_ID + "-bootstrap").equals(modId) || (MOD_ID + "_mod").equals(modId) || MOD_ID.equals(modId)) {
				return null;
			}
		} else if (formattedFile.contains("/config/")) {
			type = "config";
		} else if (formattedFile.contains("/shaderpacks/")) {
			type = "shader";
		} else if (formattedFile.contains("/resourcepacks/")) {
			type = "resourcepack";
		} else if (formattedFile.endsWith("/options.txt")) {
			type = "mc_options";
		} else {
			type = "other";
		}

		String sha1 = cache != null ? cache.getHashOrNull(file) : HashUtils.getHash(file);

		// For CF API
		String murmur = null;
		if (type.equals("mod") || type.equals("shader") || type.equals("resourcepack")) {
			murmur = sha1MurmurMapPreviousContent.get(sha1); // Get from cache
			if (murmur == null) murmur = HashUtils.getCurseforgeMurmurHash(file);
		}

		boolean isEditable = false;
		if (scanners != null && scanners.editable().hasMatch(formattedFile)) {
			isEditable = true;
			LOGGER.info("File {} is editable!", formattedFile);
		}

		boolean overwriteEditable = isEditable && scanners.overwriteEditable().matches(formattedFile);
		if (overwriteEditable) LOGGER.debug("Editable file {} is overwritten when the server changes it!", formattedFile);

		boolean forcedToCopy = false;
		if (scanners != null && scanners.forceCopy().hasMatch(formattedFile)) {
			forcedToCopy = true;
			LOGGER.info("File {} is forced to copy to standard location!", formattedFile);
		}

		return new Jsons.ModpackContentFields.ModpackContentItem(formattedFile, size, type, isEditable, overwriteEditable, forcedToCopy, sha1, murmur);
	}
}
