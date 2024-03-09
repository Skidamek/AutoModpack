package pl.skidam.automodpack_loader_core_quilt;

import org.quiltmc.loader.api.plugin.ModContainerExt;
import org.quiltmc.loader.impl.QuiltLoaderImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

// Inspired by preloading tricks by settingdust
public class QuiltLoaderImplAccessor {

    public static final Field FIELD_MODS;
    public static final Field FIELD_MOD_MAP;
    public static final Field FIELD_ADAPTER_MAP;
    public static final Method METHOD_ADD_MOD;
//    public static final Method METHOD_DUMP_MOD_LIST;
//    public static final Field FIELD_CANDIDATE_FINDERS;

    public QuiltLoaderImplAccessor() { }

    static {
        try {
            FIELD_MODS = QuiltLoaderImpl.class.getDeclaredField("mods");
            FIELD_MODS.setAccessible(true);
            FIELD_MOD_MAP = QuiltLoaderImpl.class.getDeclaredField("modMap");
            FIELD_MOD_MAP.setAccessible(true);
            FIELD_ADAPTER_MAP = QuiltLoaderImpl.class.getDeclaredField("adapterMap");
            FIELD_ADAPTER_MAP.setAccessible(true);
            METHOD_ADD_MOD = QuiltLoaderImpl.class.getDeclaredMethod("addMod", ModContainerExt.class);
            METHOD_ADD_MOD.setAccessible(true);
//            METHOD_DUMP_MOD_LIST = QuiltLoaderImpl.class.getDeclaredMethod("dumpModList", List.class);
//            METHOD_DUMP_MOD_LIST.setAccessible(true);
//            FIELD_CANDIDATE_FINDERS = ModDiscoverer.class.getDeclaredField("candidateFinders");
//            FIELD_CANDIDATE_FINDERS.setAccessible(true);
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
