package pl.skidam.automodpack_loader_core_fabric.mods;

import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.mods.ModpackLoaderService;
import pl.skidam.automodpack_loader_core.utils.VersionParser;
import pl.skidam.automodpack_loader_core_fabric_15.mods.ModpackLoader15;
import pl.skidam.automodpack_loader_core_fabric_16.mods.ModpackLoader16;

import java.nio.file.Path;
import java.util.List;

@SuppressWarnings("unused")
public class ModpackLoader implements ModpackLoaderService {

    public static final VersionParser.Version FABRIC_VERSION = VersionParser.Version.parse(new LoaderManager().getLoaderVersion().replace(".", ""));
    public static final ModpackLoaderService INSTANCE =
            FABRIC_VERSION.minor() >= 16 && FABRIC_VERSION.patch() >= 1 ? new ModpackLoader16() :
            new ModpackLoader15();

    @Override
    public void loadModpack(List<Path> modpackMods) {
        INSTANCE.loadModpack(modpackMods);
    }
}
