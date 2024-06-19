package pl.skidam.automodpack_loader_core_neoforge.mods;

import cpw.mods.modlauncher.api.ITransformationService;
import net.neoforged.fml.loading.EarlyLoadingException;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.BackgroundScanHandler;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModValidator;
import net.neoforged.neoforgespi.locating.IModFile;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Credits to settingdust - preloading tricks
public class LoadedMods {
    public static LoadedMods INSTANCE;
    public List<ModFile> candidateMods;
    private final Field fieldModValidator;
    private final ModValidator validator;
    private final Field fieldCandidateMods;

    public LoadedMods() throws NoSuchFieldException, IllegalAccessException {
        INSTANCE = this;
        fieldModValidator = FMLLoader.class.getDeclaredField("modValidator");
        fieldModValidator.setAccessible(true);

        fieldCandidateMods = ModValidator.class.getDeclaredField("candidateMods");
        fieldCandidateMods.setAccessible(true);

        validator = (ModValidator) fieldModValidator.get(null);
        // Proxy won't work with non interface
        fieldModValidator.set(null, new DummyModValidator());
    }

    private void setupModsInvoking() throws IllegalAccessException {
        fieldModValidator.set(null, validator);
        candidateMods = (List<ModFile>) fieldCandidateMods.get(validator);
    }

    private class DummyModValidator extends ModValidator {
        private static final Field fieldModFiles;

        private static final Field fieldBrokenFiles;

        private static final Field fieldDiscoveryErrorData;

        static {
            try {
                fieldModFiles = ModValidator.class.getDeclaredField("modFiles");
                fieldBrokenFiles = ModValidator.class.getDeclaredField("brokenFiles");
                fieldDiscoveryErrorData = ModValidator.class.getDeclaredField("discoveryErrorData");

                fieldModFiles.setAccessible(true);
                fieldBrokenFiles.setAccessible(true);
                fieldDiscoveryErrorData.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }


        public DummyModValidator() throws IllegalAccessException {
            super(
                    (Map<IModFile.Type, List<ModFile>>) fieldModFiles.get(validator),
                    ((List<IModFile>) fieldBrokenFiles.get(validator))
                            .stream().map(IModFile::getModFileInfo).collect(Collectors.toList()),
                    (List<EarlyLoadingException.ExceptionData>) fieldDiscoveryErrorData.get(validator));
        }

        @Override
        public BackgroundScanHandler stage2Validation() {
            try {
                setupModsInvoking();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            return validator.stage2Validation();
        }

        public void stage1Validation() {
            validator.stage1Validation();
        }

        public ITransformationService.Resource getPluginResources() {
            return validator.getPluginResources();
        }

        public ITransformationService.Resource getModResources() {
            return validator.getModResources();
        }
    }

}
