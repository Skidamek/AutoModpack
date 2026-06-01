package pl.skidam.automodpack.client.autotest;

import net.minecraft.util.FormattedCharSequence;

public final class FormattedText {
    private FormattedText() {}

    public static String toString(FormattedCharSequence sequence) {
        StringBuilder builder = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            builder.append((char) codePoint);
            return true;
        });
        return builder.toString();
    }
}
