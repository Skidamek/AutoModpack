package pl.skidam.automodpack_loader_core_quilt;

import org.quiltmc.loader.api.LanguageAdapter;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.plugin.ModContainerExt;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import pl.skidam.automodpack_loader_core.Preload;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

// Inspired by preloading tricks by settingdust
@SuppressWarnings("unused")
public class QuiltLanguageAdapter implements LanguageAdapter {

    public static final List<ModContainerExt> mods;

    static {
        try {
            mods = new ArrayList<>((List<ModContainerExt>) QuiltLoaderImplAccessor.FIELD_MODS.get(QuiltLoaderImpl.INSTANCE));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public QuiltLanguageAdapter() throws IllegalAccessException {

        QuiltLoaderImplAccessor.FIELD_MODS.set(QuiltLoaderImpl.INSTANCE, Proxy.newProxyInstance(mods.getClass().getClassLoader(), mods.getClass().getInterfaces(), new ListProxy()));

        new Preload();
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) {
        throw new UnsupportedOperationException("AutoModpack early load");
    }

    // Proxy is necessary to be able to add/remove mods there
    // See: https://gist.github.com/Skidamek/605fe5bbdd9b62a5aeef823e5a5ba3d9
    // And: https://github.com/FabricMC/fabric-loader/blob/c56386687036dbef28b065da4e3af63671240f38/src/main/java/net/fabricmc/loader/impl/FabricLoaderImpl.java#L465
    private class ListProxy implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return method.invoke(mods, args);
        }
    }
}
