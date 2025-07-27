package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.network.chat.MutableComponent;
/*? if <= 1.19.1 {*/
/*import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
*//*?} else {*/
import net.minecraft.network.chat.Component;
/*?}*/

public class VersionedText {

	/*? if <=1.19.1 {*/
    /*public static MutableComponent translatable(String key, Object... args) {
        return new TranslatableComponent(key, args);
    }

    public static MutableComponent literal(String string) {
        return new TextComponent(string);
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