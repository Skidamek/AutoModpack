package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;

@Mixin(Connection.class)
public interface ClientConnectionAccessor {
	@Accessor("channel")
	Channel getChannel();
}
