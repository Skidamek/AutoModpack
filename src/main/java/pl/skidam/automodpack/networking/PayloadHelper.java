package pl.skidam.automodpack.networking;

/*? if >=1.20.2 {*/
/*import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

// credits to fabric api
public class PayloadHelper {

    public static void write(FriendlyByteBuf byteBuf, FriendlyByteBuf data) {
        byteBuf.writeBytes(data.copy());
    }

    public static FriendlyByteBuf read(FriendlyByteBuf byteBuf, int maxSize) {
        assertSize(byteBuf, maxSize);

        FriendlyByteBuf newBuf = new FriendlyByteBuf(Unpooled.buffer());
        newBuf.writeBytes(byteBuf.copy());
        byteBuf.skipBytes(byteBuf.readableBytes());
        return newBuf;
    }

    private static void assertSize(FriendlyByteBuf buf, int maxSize) {
        int size = buf.readableBytes();

        if (size < 0 || size > maxSize) {
            throw new IllegalArgumentException("Payload may not be larger than " + maxSize + " bytes");
        }
    }
}
*//*?} else {*/