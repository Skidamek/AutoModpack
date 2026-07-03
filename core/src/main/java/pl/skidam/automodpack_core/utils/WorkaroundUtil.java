package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static pl.skidam.automodpack_core.Constants.MODPACK_LOADER;

public class WorkaroundUtil {

    public final Path modpackPath;

    public WorkaroundUtil(Path modapckPath) {
        this.modpackPath = modapckPath;
    }

    // returns list of formatted modpack files which are mods with services (these mods need special treatment in order to work properly)
    // mods returned by this method should be installed in standard `~/mods/` directory
    public Set<String> getWorkaroundMods(Jsons.ModpackContentFields modpackContentFields) throws IOException {
        Set<String> workaroundMods = new HashSet<>();

        // this workaround is needed only for neo/forge mods
        if (Constants.LOADER == null || !Constants.LOADER.contains("forge")) {
            return workaroundMods;
        }

        // Services this loader can run straight from the modpack folder; a mod shipping only these
        // never needs the copy-to-standard workaround. Decided statically, before the early-service
        // bootstrapper runs, so such a mod is never copied in the first place (a copied mod would
        // shadow in-place loading forever, since the loader defers to standard mods/).
        Set<String> handleableServices = MODPACK_LOADER == null ? Set.of() : MODPACK_LOADER.inPlaceHandleableServices();
        // Services this loader version actually handles; a service outside this set is inert here,
        // so it must not force a copy either.
        Set<String> handledServices = MODPACK_LOADER == null ? Set.of() : MODPACK_LOADER.knownServices();

        for (Jsons.ModpackContentFields.ModpackContentItem item : modpackContentFields.list) {
            if (item.type.equals("mod")) {
                Path modPath = SmartFileUtils.getPath(modpackPath, item.file);

                try (FileSystem fs = FileSystems.newFileSystem(modPath)) {
                    Set<String> services = FileInspection.getSpecificServices(fs);

                    // Consider only services the running loader version handles.
                    if (!handledServices.isEmpty()) {
                        services.retainAll(handledServices);
                    }

                    // Not a service mod, or ships nothing this loader handles.
                    if (services.isEmpty()) {
                        continue;
                    }

                    // Every service it ships can be handled in place -> leave it in the modpack
                    // folder; otherwise fall back to copy-to-standard.
                    if (handleableServices.containsAll(services)) {
                        continue;
                    }

                    workaroundMods.add(item.file);
                }
            }
        }

        return workaroundMods;
    }
}