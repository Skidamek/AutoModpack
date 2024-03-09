package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.text.*;

public class VersionedText {

//#if MC < 1192
//$$
//$$     public static MutableText translatable(String key, Object... args) {
//$$         return new TranslatableText(key, args);
//$$     }
//$$
//$$     public static MutableText literal(String string) {
//$$         return new LiteralText(string);
//$$     }
//$$
//#else

public static MutableText translatable(String key, Object... args) {
   return Text.translatable(key, args);
}

public static MutableText literal(String string) {
   return Text.literal(string);
}

//#endif
}
