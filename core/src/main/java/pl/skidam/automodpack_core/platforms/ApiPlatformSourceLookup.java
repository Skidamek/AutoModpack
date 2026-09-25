package pl.skidam.automodpack_core.platforms;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import pl.skidam.automodpack_core.utils.HashUtils;

/** One best-effort round per platform; any failure leaves the object unresolvable instead of delaying or failing the lookup. */
public class ApiPlatformSourceLookup implements PlatformSourceLookup {

	@Override
	public Map<String, Long> platformSizes(Collection<Query> queries) {
		if (queries == null || queries.isEmpty()) return Map.of();
		Set<String> wanted = Set.copyOf(queries.stream().map(Query::sha1).toList());
		Map<String, Long> served = new HashMap<>();
		List<String> sha1s = new ArrayList<>();
		Map<String, String> murmurs = new LinkedHashMap<>();
		for (Query query : queries) {
			sha1s.add(query.sha1());
			try {
				String murmur = HashUtils.getCurseforgeMurmurHash(query.objectPath());
				if (murmur != null) murmurs.put(query.sha1(), murmur);
			} catch (IOException e) {
				LOGGER.debug("Could not hash {} for CurseForge fingerprinting", query.objectPath(), e);
			}
		}
		List<ModrinthAPI> modrinthInfos = ModrinthAPI.getModsInfosFromListOfSHA1(sha1s);
		if (modrinthInfos != null) for (ModrinthAPI info : modrinthInfos) putServed(served, wanted, info.SHA1Hash(), info.fileSize());
		if (!murmurs.isEmpty()) {
			List<CurseForgeAPI> curseForgeInfos = CurseForgeAPI.getModInfosFromFingerPrints(murmurs);
			if (curseForgeInfos != null) for (CurseForgeAPI info : curseForgeInfos) {
				try {
					putServed(served, wanted, info.sha1Hash(), Long.parseLong(info.fileSize()));
				} catch (NumberFormatException e) {
					LOGGER.debug("CurseForge reported an unparsable file size for {}", info.sha1Hash());
				}
			}
		}
		return served;
	}

	private static void putServed(Map<String, Long> served, Set<String> wanted, String sha1, long size) {
		if (sha1 == null) return;
		String normalized = sha1.toLowerCase(Locale.ROOT);
		if (wanted.contains(normalized)) served.put(normalized, size);
	}
}
