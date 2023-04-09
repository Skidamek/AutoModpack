package pl.skidam.automodpack.forge.networking.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.ClientLoginPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraftforge.network.NetworkEvent;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ModpackUtils;
import pl.skidam.automodpack.config.Jsons;

import java.io.File;
import java.util.function.Supplier;

import static pl.skidam.automodpack.StaticVariables.*;

public class LinkS2CPacket implements Packet<ClientLoginPacketListener> {
    private final String link;
    public LinkS2CPacket(String link) {
        this.link = link;
    }

    public LinkS2CPacket(PacketByteBuf buf) {
        this.link = buf.readString();
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(this.link);
    }

    public void apply(ClientLoginPacketListener listener) {
        LOGGER.error("Received link packet from server! " + link);
    }

    public void apply(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            LOGGER.error("Received link packet from server! " + link);
            ClientLink = link;
            String modpackFileName = link.substring(link.lastIndexOf("/") + 1); // removes https:// and http://
            modpackFileName = modpackFileName.replace(":", "-"); // replaces : with -
            File modpackDir = new File(modpacksDir + File.separator + modpackFileName);
            Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(selectedModpackLink);
            new Thread(() -> new ModpackUpdater(serverModpackContent, link, modpackDir)).start();
        });
    }
}
