package pl.skidam.automodpack_loader_core_forge.mods;

import net.minecraftforge.forgespi.locating.IModFile;

import java.util.Collection;
import java.util.List;

public class LoadedMods {
    public static LoadedMods INSTANCE;
    private final List<IModFile> mods;

    public LoadedMods(List<IModFile> mods) {
        this.mods = mods;
        INSTANCE = this;
    }

    public Collection<IModFile> all() {
        return this.mods;
    }
}
