package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
/*? if <= 1.19.1 {*/
/*import net.minecraft.text.*;
*//*?}*/

public class VersionedText {

	/*? if <=1.19.1 {*/
    /*public static MutableText translatable(String key, Object... args) {
        return new TranslatableText(key, args);
    }

    public static MutableText literal(String string) {
        return new LiteralText(string);
    }

	*//*?} else {*/
	public static MutableComponent translatable(String key, Object... args) {
		return Component.translatable(key, args);
	}

	public static MutableComponent literal(String string) {
		return Component.literal(string);
	}
	/*?}*/
}