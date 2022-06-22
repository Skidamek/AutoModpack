package pl.skidam.automodpack.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

import static pl.skidam.automodpack.config.Config.*;

@Config(name = "automodpack")
public class ClothConfig implements ConfigData {
    @ConfigEntry.Category("CLIENT SIDE")
    @Comment("Danger screen before download/update modpack")
    public static boolean danger_screen = true;
    @ConfigEntry.Category("CLIENT SIDE")
    @Comment("Shows \"Check updates!\" button on the title screen")
    public static boolean check_updates_button = true;

    @ConfigEntry.Category("SERVER SIDE")
    @Comment("Clone mods from mods loaded on server to modpack")
    public boolean clone_mods = true;
    @ConfigEntry.Category("SERVER SIDE")
    @Comment("Sync mods from modpack to server mods. AKA: Delete all not cloned mods NOTE: This will automatically enable clone_mods")
    public boolean sync_mods = true;
    @ConfigEntry.Category("SERVER SIDE")
    @Comment("Port to host http server for modpack on server")
    public int host_port = 30037;
    @ConfigEntry.Category("SERVER SIDE")
    @Comment("Thread count to host http server for modpack on server")
    public int host_thread_count = 2;
    @ConfigEntry.Category("SERVER SIDE")
    @Comment("External IP to host http server for modpack on server NOTE: If you dont know what this is, leave it empty")
    public String host_external_ip = "";

    @ConfigEntry.Category("SERVER SIDE")
    @Comment("External modpack host server (if you put something there modpack host will be auto disabled)")
    public String external_host_server = ""; // TODO: add support for external host server
}
