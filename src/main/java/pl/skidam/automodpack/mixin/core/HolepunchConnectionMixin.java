package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;

import pl.skidam.automodpack_core.protocol.ServerHolepunchBridge;

@Mixin(Connection.class)
public abstract class HolepunchConnectionMixin {

	// A detached Connection has no login listener and no disconnect reason, but vanilla still drops
	// it through here once the holepunch channel closes. Without this skip, a Connection whose
	// reason was set while detached (stray packet during takeover) would NPE on the null listener.
	@WrapMethod(method = "handleDisconnection")
	private void automodpack$dropDetachedSilently(Operation<Void> original) {
		Channel channel = ((HolepunchConnectionAccessor) this).automodpack$getChannel();
		if (channel != null && channel.hasAttr(ServerHolepunchBridge.DETACHED_MARKER)) return;
		original.call();
	}
}
