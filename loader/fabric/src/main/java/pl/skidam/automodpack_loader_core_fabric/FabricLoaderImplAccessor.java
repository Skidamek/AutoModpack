package pl.skidam.automodpack_loader_core_fabric;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.discovery.ModCandidate;
import net.fabricmc.loader.impl.discovery.ModDiscoverer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

// Inspired by preloading tricks by settingdust
public class FabricLoaderImplAccessor {

    public static final Field FIELD_MODS;
    public static final Field FIELD_MOD_MAP;
    public static final Field FIELD_ADAPTER_MAP;
    public static final Method METHOD_DUMP_MOD_LIST;
    public static final Field FIELD_CANDIDATE_FINDERS;

    static {
        try {
            FIELD_MODS = FabricLoaderImpl.class.getDeclaredField("mods");
            FIELD_MODS.setAccessible(true);
            FIELD_MOD_MAP = FabricLoaderImpl.class.getDeclaredField("modMap");
            FIELD_MOD_MAP.setAccessible(true);
            FIELD_ADAPTER_MAP = FabricLoaderImpl.class.getDeclaredField("adapterMap");
            FIELD_ADAPTER_MAP.setAccessible(true);
            METHOD_DUMP_MOD_LIST = FabricLoaderImpl.class.getDeclaredMethod("dumpModList", List.class);
            METHOD_DUMP_MOD_LIST.setAccessible(true);
            FIELD_CANDIDATE_FINDERS = ModDiscoverer.class.getDeclaredField("candidateFinders");
            FIELD_CANDIDATE_FINDERS.setAccessible(true);

        } catch (NoSuchMethodException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
}
