package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class DangerScreen extends VersionedScreen {

    private static final int ACKNOWLEDGEMENT_TIMER_SECONDS = 10;

    private final Screen parent;
    private final ModpackUpdater modpackUpdaterInstance;
    private final Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate;
    private final List<String> unverifiedJarNames;
    private final boolean hasUnverifiedJars;
    private Button cancelButton;
    private Button confirmButton;
    private Button acknowledgementButton;
    private boolean acknowledged;
    private int ticksRemaining;

    public DangerScreen(Screen parent, ModpackUpdater modpackUpdaterInstance) {
        this(parent, modpackUpdaterInstance, Set.of());
    }

    public DangerScreen(Screen parent, ModpackUpdater modpackUpdaterInstance, Collection<?> filesToUpdate) {
        super(VersionedText.literal("DangerScreen"));
        this.parent = parent;
        this.modpackUpdaterInstance = modpackUpdaterInstance;
        this.filesToUpdate = castItems(filesToUpdate);
        this.unverifiedJarNames = modpackUpdaterInstance.getUnverifiedJarFiles().stream()
                .map(item -> item.file)
                .sorted(Comparator.naturalOrder())
                .toList();
        this.hasUnverifiedJars = !unverifiedJarNames.isEmpty();
        this.ticksRemaining = hasUnverifiedJars ? ACKNOWLEDGEMENT_TIMER_SECONDS * 20 : 0;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    private static Set<Jsons.ModpackContentFields.ModpackContentItem> castItems(Collection<?> items) {
        if (items == null || items.isEmpty()) {
            return Set.of();
        }

        List<Jsons.ModpackContentFields.ModpackContentItem> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Jsons.ModpackContentFields.ModpackContentItem contentItem) {
                result.add(contentItem);
            }
        }
        return Set.copyOf(result);
    }

    @Override
    protected void init() {
        super.init();

        int buttonY = this.height / 2 + (hasUnverifiedJars ? 94 : 50);
        this.cancelButton = buttonWidget(
            this.width / 2 - 145,
            buttonY,
            140,
            20,
            VersionedText.translatable("automodpack.danger.cancel").withStyle(ChatFormatting.BOLD),
            button -> this.minecraft.gui.setScreen(parent)
        );
        this.addRenderableWidget(this.cancelButton);

        this.confirmButton = buttonWidget(
            this.width / 2 + 5,
            buttonY,
            140,
            20,
            VersionedText.translatable(hasUnverifiedJars
                    ? "automodpack.danger.unverified.confirm"
                    : "automodpack.danger.confirm").withStyle(ChatFormatting.RED),
            button -> confirmDownload()
        );
        this.confirmButton.active = !hasUnverifiedJars || acknowledged;
        this.addRenderableWidget(this.confirmButton);

        if (hasUnverifiedJars) {
            this.acknowledgementButton = buttonWidget(
                this.width / 2 - 180,
                this.height / 2 + 68,
                360,
                20,
                acknowledgementText(),
                button -> acknowledge()
            );
            // Keep the acknowledgement visible while it is locked by the timer.
            this.acknowledgementButton.active = ticksRemaining == 0;
            this.addRenderableWidget(this.acknowledgementButton);
        }

        // Keep the safe action selected when the warning opens. In particular, do not
        // make pressing Enter an implicit approval to download executable files.
        this.setInitialFocus(this.cancelButton);
    }

    private void acknowledge() {
        if (ticksRemaining > 0) {
            return;
        }

        acknowledged = !acknowledged;
        acknowledgementButton.setMessage(acknowledgementText());
        confirmButton.active = acknowledged;
    }

    private net.minecraft.network.chat.MutableComponent acknowledgementText() {
        var text = VersionedText.literal(acknowledged ? "[x] " : "[ ] ")
                .append(VersionedText.translatable("automodpack.danger.acknowledge"));
        if (ticksRemaining > 0) {
            text.append(VersionedText.literal(" (" + getRemainingSeconds() + "s)"));
        }
        return text;
    }

    private void confirmDownload() {
        if (hasUnverifiedJars && (!acknowledged || ticksRemaining > 0)) {
            return;
        }

        this.confirmButton.active = false;
        Util.backgroundExecutor().execute(() -> modpackUpdaterInstance.startUpdate(filesToUpdate));
    }

    @Override
    public void tick() {
        super.tick();
        if (!hasUnverifiedJars || ticksRemaining <= 0) {
            return;
        }

        ticksRemaining--;
        acknowledgementButton.setMessage(acknowledgementText());
        if (ticksRemaining == 0) {
            acknowledgementButton.active = true;
        }
    }

    private int getRemainingSeconds() {
        return (ticksRemaining + 19) / 20;
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        if (hasUnverifiedJars) {
            renderUnverifiedJarWarning(matrices);
        } else {
            renderGeneralWarning(matrices);
        }
    }

    private void renderGeneralWarning(VersionedMatrices matrices) {
        int center = this.height / 2;

        drawLine(matrices, titleKey(), center - 66, TextColors.WHITE, true);
        drawLine(matrices, "automodpack.danger.description", center - 45, TextColors.WHITE, false);
        drawLine(matrices, "automodpack.danger.secDescription", center - 33, TextColors.WHITE, false);
        drawLine(matrices, "automodpack.danger.thiDescription", center - 21, TextColors.WHITE, false);
        drawLine(matrices, "automodpack.danger.warning1", center - 3, TextColors.WHITE, false);
        drawLine(matrices, "automodpack.danger.warning7", center + 9, TextColors.WHITE, false);
    }

    private void renderUnverifiedJarWarning(VersionedMatrices matrices) {
        int center = this.height / 2;

        drawLine(matrices, titleKey(), center - 100, TextColors.WHITE, true);
        drawLine(matrices, "automodpack.danger.unverified.description", center - 86, TextColors.WHITE, false);
        drawLine(matrices, "automodpack.danger.unverified.secDescription", center - 74, TextColors.LIGHT_RED, false);
        drawLine(matrices, "automodpack.danger.unverified.thiDescription", center - 62, TextColors.LIGHT_RED, false);
        drawLine(matrices, "automodpack.danger.unverified.warning1", center - 45, TextColors.LIGHT_RED, false);
        drawLine(matrices, "automodpack.danger.unverified.warning2", center - 34, TextColors.WHITE, false);
        drawLine(matrices, "automodpack.danger.unverified.warning3", center - 17, TextColors.WHITE, false);
        drawLine(matrices, "automodpack.danger.unverified.warning4", center - 5, TextColors.LIGHT_RED, false);
        drawLine(matrices, "automodpack.danger.unverified.warning5", center + 7, TextColors.WHITE, false);
        drawLine(matrices, "automodpack.danger.unverified.files", center + 24, TextColors.LIGHT_RED, false,
                unverifiedJarNames.size());

        int fileY = center + 35;
        int displayedFiles = Math.min(3, unverifiedJarNames.size());
        for (int i = 0; i < displayedFiles; i++) {
            drawCenteredTextWithShadow(
                    matrices,
                    this.font,
                    VersionedText.literal("- " + shorten(unverifiedJarNames.get(i))),
                    this.width / 2,
                    fileY + i * 10,
                    TextColors.WHITE
            );
        }

        if (unverifiedJarNames.size() > displayedFiles) {
            drawLine(matrices, "automodpack.danger.unverified.more", fileY + displayedFiles * 10,
                    TextColors.WHITE, false, unverifiedJarNames.size() - displayedFiles);
        }
    }

    private String titleKey() {
        return modpackUpdaterInstance.fullDownload
                ? "automodpack.danger"
                : "automodpack.danger.unverified";
    }

    private void drawLine(VersionedMatrices matrices, String key, int y, int color, boolean bold, Object... args) {
        var text = VersionedText.translatable(key, args);
        if (bold) {
            text.withStyle(ChatFormatting.BOLD);
        }
        drawCenteredTextWithShadow(matrices, this.font, text, this.width / 2, y, color);
    }

    private static String shorten(String file) {
        if (file.length() <= 58) {
            return file;
        }
        return file.substring(0, 27) + "..." + file.substring(file.length() - 28);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
