package pl.skidam.automodpack_loader_core_neoforge.mods;

import net.neoforged.neoforgespi.locating.IModFile;

import java.util.Collection;

public record LoadedMods(Collection<IModFile> mods) {
    public static LoadedMods INSTANCE;

    public LoadedMods(Collection<IModFile> mods) {
        this.mods = mods;
        INSTANCE = this;
    }
}
