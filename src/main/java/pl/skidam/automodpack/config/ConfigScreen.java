package pl.skidam.automodpack.config;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ConfigScreen {
    public static Screen createConfigGui(Config config, Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setTitle(Text.literal("AutoModpack"))
                .setSavingRunnable(config::save)
                .setParentScreen(parent);

        ConfigCategory ClientSideCategory = builder.getOrCreateCategory(Text.literal("Client side"));
        AbstractConfigListEntry<?> showUpdatesButton = builder.entryBuilder()
                .startBooleanToggle(Text.literal("Show \"Check updates!\" button"), Config.CHECK_UPDATES_BUTTON)
                .setTooltip(Text.literal("You will no longer see \"Check updates!\" button on Title Screen."))
                .setSaveConsumer((toggled) -> Config.CHECK_UPDATES_BUTTON = toggled)
                .setDefaultValue(true)
                .build();
        ClientSideCategory.addEntry(showUpdatesButton);

        AbstractConfigListEntry<?> showDangerScreen = builder.entryBuilder()
                .startBooleanToggle(Text.literal("Show Danger Screen"), Config.DANGER_SCREEN)
                .setTooltip(Text.literal("You will no longer see the Danger Screen before downloading updates."))
                .setSaveConsumer((toggled) -> Config.DANGER_SCREEN = toggled)
                .setDefaultValue(true)
                .build();
        ClientSideCategory.addEntry(showDangerScreen);

        ConfigCategory ServerSideCategory = builder.getOrCreateCategory(Text.literal("Server side"));
        AbstractConfigListEntry<?> syncMods = builder.entryBuilder()
                .startBooleanToggle(Text.literal("Sync mods"), Config.CHECK_UPDATES_BUTTON)
                .setTooltip(Text.literal("Mods in modpack will be no longer synced with server mods."))
                .setSaveConsumer((toggled) -> Config.CHECK_UPDATES_BUTTON = toggled)
                .setDefaultValue(true)
                .build();
        ServerSideCategory.addEntry(syncMods);

        return builder.build();
    }
}

