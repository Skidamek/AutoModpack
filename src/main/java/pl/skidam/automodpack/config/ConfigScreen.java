package pl.skidam.automodpack.config;

import me.shedaniel.clothconfig2.api.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class ConfigScreen {
    public static Screen createConfigGui(Config config, Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setTitle(Text.literal("AutoModpack"))
                .setSavingRunnable(config::save)
                .setParentScreen(parent);

        ConfigCategory configCategory = builder.getOrCreateCategory(Text.literal("Config"));
        AbstractConfigListEntry<?> showDangerScreen = builder.entryBuilder()
                .startBooleanToggle(Text.literal("Show Danger Screen"), Config.DANGER_SCREEN)
                .setTooltip(Text.literal("Show Danger Screen before downloading updates."))
                .setSaveConsumer((toggled) -> Config.DANGER_SCREEN = toggled)
                .setDefaultValue(true)
                .build();
        configCategory.addEntry(showDangerScreen);

        AbstractConfigListEntry<?> showCheckUpdatesButton = builder.entryBuilder()
                .startBooleanToggle(Text.literal("Show \"Check updates!\" button"), Config.CHECK_UPDATES_BUTTON)
                .setTooltip(Text.literal("Show \"Check updates!\" button on Title Screen."))
                .setSaveConsumer((toggled) -> Config.CHECK_UPDATES_BUTTON = toggled)
                .setDefaultValue(true)
                .build();
        configCategory.addEntry(showCheckUpdatesButton);

        AbstractConfigListEntry<?> showDeleteModpackButton = builder.entryBuilder()
                .startBooleanToggle(Text.literal("Show \"Delete modpack!\" button"), Config.DELETE_MODPACK_BUTTON)
                .setTooltip(Text.literal("Show \"Delete modpack!\" button on Title Screen. Button is only visible when some modpack is installed."))
                .setSaveConsumer((toggled) -> Config.DELETE_MODPACK_BUTTON = toggled)
                .setDefaultValue(true)
                .build();
        configCategory.addEntry(showDeleteModpackButton);

        configCategory.addEntry(builder.entryBuilder()
                .startTextDescription(Text.literal("WARNING: Configs below this message work only on servers! ⬇").formatted(Formatting.RED))
                .build()
        );

        // TODO if player is on server where has op permission, send this config to server and reload this config on server

        AbstractConfigListEntry<?> modpackHost = builder.entryBuilder()
                .startBooleanToggle(Text.literal("Host modpack"), Config.MODPACK_HOST)
                .setTooltip(Text.literal("Host http server for modpack. If this is disabled use \"External host server\"."))
                .setSaveConsumer((toggled) -> Config.MODPACK_HOST = toggled)
                .setDefaultValue(true)
                .build();
        configCategory.addEntry(modpackHost);

        AbstractConfigListEntry<?> syncMods = builder.entryBuilder()
                .startBooleanToggle(Text.literal("Sync mods"), Config.SYNC_MODS)
                .setTooltip(Text.literal("Clone all mods from default mods folder on your server to the modpack, but all other mods will be deleted. (you **can** add mods that you don't want to load on the server only in `./AutoModpack/modpack/[CLIENT] mods`, delmods.txt **is** making automatically when some mods got updated or deleted)"))
                .setSaveConsumer((toggled) -> Config.SYNC_MODS = toggled)
                .setDefaultValue(true)
                .build();
        configCategory.addEntry(syncMods);

        AbstractConfigListEntry<?> onlyOptionalModpack = builder.entryBuilder()
                .startBooleanToggle(Text.literal("Only optional modpack"), Config.ONLY_OPTIONAL_MODPACK)
                .setTooltip(Text.literal("If this is enabled, vanilla players will, can join if other mods on server are only server side."))
                .setSaveConsumer((toggled) -> Config.ONLY_OPTIONAL_MODPACK = toggled)
                .setDefaultValue(false)
                .build();
        configCategory.addEntry(onlyOptionalModpack);

        AbstractConfigListEntry<?> hostPort = builder.entryBuilder()
                .startIntField(Text.literal("Host port"), Config.HOST_PORT)
                .setTooltip(Text.literal("At this port http server for hosting modpack will be running."))
                .setSaveConsumer((integer) -> Config.HOST_PORT = integer)
                .setDefaultValue(30037)
                .build();
        configCategory.addEntry(hostPort);

        AbstractConfigListEntry<?> hostThreadCount = builder.entryBuilder()
                .startIntField(Text.literal("Host thread count"), Config.HOST_THREAD_COUNT)
                .setTooltip(Text.literal("Http server will be use this amount of threads."))
                .setSaveConsumer((integer) -> Config.HOST_THREAD_COUNT = integer)
                .setDefaultValue(2)
                .build();
        configCategory.addEntry(hostThreadCount);

        AbstractConfigListEntry<?> hostExternalIP = builder.entryBuilder()
                .startStrField(Text.literal("Host external IP"), Config.HOST_EXTERNAL_IP)
                .setTooltip(Text.literal("Http server will be use this external ip instead of default one."))
                .setSaveConsumer((string) -> Config.HOST_EXTERNAL_IP = string)
                .setDefaultValue("")
                .build();
        configCategory.addEntry(hostExternalIP);

        AbstractConfigListEntry<?> externalHostServer = builder.entryBuilder()
                .startStrField(Text.literal("External modpack host"), Config.EXTERNAL_MODPACK_HOST)
                .setTooltip(Text.literal("Typed here http/s address will be used as external host server. This will automatically disable Http server."))
                .setSaveConsumer((string) -> Config.EXTERNAL_MODPACK_HOST = string)
                .setDefaultValue("")
                .build();
        configCategory.addEntry(externalHostServer);

        return builder.build();
    }
}