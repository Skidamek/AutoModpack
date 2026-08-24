package pl.skidam.automodpack.mixin.core;

import java.util.UUID;

/*? if <= 1.19.1 {*/
/*import com.mojang.authlib.GameProfile;
*//*?} else {*/
/*?}*/
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
/*? if >=1.19.2 {*/
import net.minecraft.network.chat.Component;
/*?} else {*/
/*import net.minecraft.network.chat.TextComponent;
*//*?}*/
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import pl.skidam.mcholepunch.internal.DebugLog;
import pl.skidam.mcholepunch.internal.protocol.HolepunchMarker;
import pl.skidam.mcholepunch.server.HolepunchServerRegistry;
import pl.skidam.mcholepunch.server.netty.NettyLoginNegotiator;
import pl.skidam.mcholepunch.server.netty.NettyTakeoverSpec;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class HolepunchServerLoginMixin {
	@Shadow
	@Final
	private MinecraftServer server;

	@Shadow
	@Final
	private Connection connection;

	@WrapMethod(method = "handleHello")
	private void automodpack$handleHello(ServerboundHelloPacket packet, Operation<Void> original) {
		String username = username(packet);
		UUID profileId = profileId(packet);
		HolepunchMarker marker = HolepunchMarker.decode(profileId).orElse(null);
		if (marker == null) {
			original.call(packet);
			return;
		}

		if (DebugLog.enabled()) {
			System.err.printf("[mcholepunch-debug][%s][LOGIN] marker accepted name=%s profileId=%s remote=%s%n", Thread.currentThread().getName(), username, profileId, connection.getRemoteAddress());
		}
		HolepunchServerRegistry.Registration registration = HolepunchServerRegistry.current();
		if (registration == null) {
			/*? if >=1.19.2 {*/
			connection.disconnect(Component.literal("Holepunch unavailable"));
			/*?} else {*/
			/*connection.disconnect(new TextComponent("Holepunch unavailable"));
			*//*?}*/
			return;
		}
		Channel channel = ((HolepunchConnectionAccessor) connection).automodpack$getChannel();
		new NettyLoginNegotiator(channel, server.getKeyPair(), username, channel.remoteAddress(), marker, registration, NettyTakeoverSpec.minecraftLogin()).start();
	}

	@Unique
	private static String username(ServerboundHelloPacket packet) {
		/*? if <= 1.19.1 {*/
		/*GameProfile profile = packet.getGameProfile();
		return profile.getName();
		*//*?} else {*/
		return packet.name();
		/*?}*/
	}

	@Unique
	private static UUID profileId(ServerboundHelloPacket packet) {
		/*? if <= 1.19.1 {*/
		/*return null;
		*//*?} else if <= 1.20.1 {*/
		/*return packet.profileId().orElse(null);
		*//*?} else {*/
		return packet.profileId();
		/*?}*/
	}
}
