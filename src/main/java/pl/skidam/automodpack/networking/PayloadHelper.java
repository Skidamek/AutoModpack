package pl.skidam.automodpack.networking;

/*? if >=1.20.2 {*/
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

// credits to fabric api
public class PayloadHelper {

    public static PacketByteBuf read(PacketByteBuf byteBuf, int maxSize) {
        assertSize(byteBuf, maxSize);

        PacketByteBuf newBuf = new PacketByteBuf(Unpooled.buffer());
        newBuf.writeBytes(byteBuf.copy());
        byteBuf.skipBytes(byteBuf.readableBytes());
        return newBuf;
    }

    private static void assertSize(PacketByteBuf buf, int maxSize) {
        int size = buf.readableBytes();

        if (size < 0 || size > maxSize) {
            throw new IllegalArgumentException("Payload may not be larger than " + maxSize + " bytes");
        }
    }
}
/*?} else {*/