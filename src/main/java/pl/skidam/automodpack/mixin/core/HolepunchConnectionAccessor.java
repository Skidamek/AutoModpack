package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;

@Mixin(Connection.class)
public interface HolepunchConnectionAccessor {
	@Accessor("channel")
	Channel automodpack$getChannel();

	@Accessor("packetListener")
	void automodpack$setPacketListener(PacketListener listener);
}
