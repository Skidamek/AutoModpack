package pl.skidam.automodpack.config;

import me.shedaniel.clothconfig2.api.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
public class ConfigScreen {
    public static Screen createConfigGui(Config config, Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setTitle(new LiteralText("AutoModpack"))
                .setSavingRunnable(config::save)
                .setParentScreen(parent);

        ConfigCategory configCategory = builder.getOrCreateCategory(new LiteralText("Config"));
        AbstractConfigListEntry<?> showDangerScreen = builder.entryBuilder()
                .startBooleanToggle(new LiteralText("Show Danger Screen"), Config.DANGER_SCREEN)
                .setTooltip(new LiteralText("Show Danger Screen before downloading updates."))
                .setSaveConsumer((toggled) -> Config.DANGER_SCREEN = toggled)
                .setDefaultValue(true)
                .build();
        configCategory.addEntry(showDangerScreen);

        AbstractConfigListEntry<?> showCheckUpdatesButton = builder.entryBuilder()
                .startBooleanToggle(new LiteralText("Show \"Check updates!\" button"), Config.CHECK_UPDATES_BUTTON)
                .setTooltip(new LiteralText("Show \"Check updates!\" button on Title Screen."))
                .setSaveConsumer((toggled) -> Config.CHECK_UPDATES_BUTTON = toggled)
                .setDefaultValue(true)
                .build();
        configCategory.addEntry(showCheckUpdatesButton);

        AbstractConfigListEntry<?> showDeleteModpackButton = builder.entryBuilder()
                .startBooleanToggle(new LiteralText("Show \"Delete modpack!\" button"), Config.DELETE_MODPACK_BUTTON)
                .setTooltip(new LiteralText("Show \"Delete modpack!\" button on Title Screen. Button is only visible when some modpack is installed."))
                .setSaveConsumer((toggled) -> Config.DELETE_MODPACK_BUTTON = toggled)
                .setDefaultValue(true)
                .build();
        configCategory.addEntry(showDeleteModpackButton);

        configCategory.addEntry(builder.entryBuilder()
                .startTextDescription(new LiteralText("WARNING: Configs below this message work only on servers! ⬇").formatted(Formatting.RED))
                .build()
        );

        // TODO if player is on server where has op permission, send this config to server and reload this config on server

        AbstractConfigListEntry<?> modpackHost = builder.entryBuilder()
                .startBooleanToggle(new LiteralText("Host modpack"), Config.MODPACK_HOST)
                .setTooltip(new LiteralText("Host http server for modpack. If this is disabled use \"External host server\"."))
                .setSaveConsumer((toggled) -> Config.MODPACK_HOST = toggled)
                .setDefaultValue(true)
                .build();
        configCategory.addEntry(modpackHost);

        AbstractConfigListEntry<?> syncMods = builder.entryBuilder()
                .startBooleanToggle(new LiteralText("Sync mods"), Config.SYNC_MODS)
                .setTooltip(new LiteralText("Its the same as \"Clone mods\" but here all other mods will be deleted."))
                .setSaveConsumer((toggled) -> Config.SYNC_MODS = toggled)
                .setDefaultValue(false)
                .build();
        configCategory.addEntry(syncMods);

        AbstractConfigListEntry<?> hostPort = builder.entryBuilder()
                .startIntField(new LiteralText("Host port"), Config.HOST_PORT)
                .setTooltip(new LiteralText("At this port http server for hosting modpack will be running."))
                .setSaveConsumer((integer) -> Config.HOST_PORT = integer)
                .setDefaultValue(30037)
                .build();
        configCategory.addEntry(hostPort);

        AbstractConfigListEntry<?> hostThreadCount = builder.entryBuilder()
                .startIntField(new LiteralText("Host thread count"), Config.HOST_THREAD_COUNT)
                .setTooltip(new LiteralText("Http server will be use this amount of threads."))
                .setSaveConsumer((integer) -> Config.HOST_THREAD_COUNT = integer)
                .setDefaultValue(2)
                .build();
        configCategory.addEntry(hostThreadCount);

        AbstractConfigListEntry<?> hostExternalIP = builder.entryBuilder()
                .startStrField(new LiteralText("Host external IP"), Config.HOST_EXTERNAL_IP)
                .setTooltip(new LiteralText("Http server will be use this external ip instead of default one."))
                .setSaveConsumer((string) -> Config.HOST_EXTERNAL_IP = string)
                .setDefaultValue("")
                .build();
        configCategory.addEntry(hostExternalIP);

        AbstractConfigListEntry<?> externalHostServer = builder.entryBuilder()
                .startStrField(new LiteralText("External modpack host"), Config.EXTERNAL_MODPACK_HOST)
                .setTooltip(new LiteralText("Typed here http/s address will be used as external host server. This will automatically disable Http server."))
                .setSaveConsumer((string) -> Config.EXTERNAL_MODPACK_HOST = string)
                .setDefaultValue("")
                .build();
        configCategory.addEntry(externalHostServer);


        return builder.build();
    }
}