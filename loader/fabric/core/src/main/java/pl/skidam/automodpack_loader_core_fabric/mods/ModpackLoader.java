package pl.skidam.automodpack_loader_core_fabric.mods;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_loader_core.utils.VersionParser;
import pl.skidam.automodpack_loader_core_fabric_15.mods.ModpackLoader15;
import pl.skidam.automodpack_loader_core_fabric_16.mods.ModpackLoader16;

import java.nio.file.Path;
import java.util.List;

import static pl.skidam.automodpack_core.GlobalVariables.LOADER_MANAGER;

@SuppressWarnings("unused")
public class ModpackLoader implements ModpackLoaderService {

    public static final Integer[] FABRIC_VERSION = VersionParser.parseVersion(LOADER_MANAGER.getLoaderVersion());
    public static final ModpackLoaderService INSTANCE =
            VersionParser.isGreaterOrEqual(FABRIC_VERSION, VersionParser.parseVersion("0.16.1")) ? new ModpackLoader16() :
            new ModpackLoader15();

    @Override
    public void loadModpack(List<Path> modpackMods) {
        INSTANCE.loadModpack(modpackMods);
    }

    @Override
    public List<LoaderManagerService.Mod> getModpackNestedConflicts(Path modpackDir) {
        return INSTANCE.getModpackNestedConflicts(modpackDir);
    }
}