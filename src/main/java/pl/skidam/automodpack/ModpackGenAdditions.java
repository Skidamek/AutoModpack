package pl.skidam.automodpack;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.loader.LoaderService;
import pl.skidam.automodpack_core.modpack.Modpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ModpackGenAdditions {

    public static boolean generate() {
        Modpack mainModpack = new Modpack();
        boolean generated = mainModpack.generate();

        if (!generated) {
            return false;
        }

        // TODO comeback auto excluding but better and in core/server module (make universal function)
        // check how mod loaders check that and copy

//        if (serverConfig.autoExcludeServerSideMods) {
//            autoExcludeServerMods(Modpack.Content.list);
//            Modpack.Content.saveModpackContent();
////            runFileChecker();
//        }

        return true;
    }


    private static void autoExcludeServerMods(List<Jsons.ModpackContentFields.ModpackContentItem> contentItemList) {

        List<String> removeSimilar = new ArrayList<>();

        Collection<LoaderService.Mod> modList = new LoaderManager().getModList();

        if (modList == null) {
            LOGGER.error("Failed to get mod contentItemList!");
            return;
        }


        for (var mod : modList) {
            String modId = mod.modID();
            if (mod.    environmentType() == LoaderService.EnvironmentType.SERVER) {
                contentItemList.removeIf(modpackContentItems -> {
                    if (modpackContentItems.file.contains(mod.modPath().getFileName().toString())) {
                        LOGGER.info("Mod {} has been auto excluded from modpack because it is server side mod", modId);
                        removeSimilar.add(modId);
                        return true;
                    }
                    return false;
                });
            }
        }

        for (var modId : removeSimilar) {
            contentItemList.removeIf(modpackContentItems -> {
                if (modpackContentItems.type.equals("mod")) return false;
                var contentFileName = String.valueOf(hostModpackContentFile.getFileName());
                if (contentFileName.contains(modId)) {
                    LOGGER.info("File {} has been auto excluded from modpack because mod of this file is already excluded", contentFileName);
                    return true;
                }
                return false;
            });
        }
    }
}
