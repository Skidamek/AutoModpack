package pl.skidam.automodpack;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/**
 * Utility class for ease of porting to different Minecraft versions.
 */
public final class TextHelper {

    public static MutableText translatable(String key, Object... args) {
        return Text.translatable(key, args);
    }

    public static MutableText literal(String string) {
        return Text.literal(string);
    }
}