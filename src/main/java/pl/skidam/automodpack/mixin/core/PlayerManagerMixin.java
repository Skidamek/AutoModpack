package pl.skidam.automodpack.mixin.core;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack.modpack.GameHelpers;

/*? if >1.20.3 {*/
import net.minecraft.server.network.CommonListenerCookie;
/*?}*/

import static pl.skidam.automodpack_core.Constants.serverConfig;

@Mixin(PlayerList.class)
public class PlayerManagerMixin {

/*? if >1.20.3 {*/
	@WrapMethod(method = "placeNewPlayer")
	private void onPlayerConnect(Connection connection, ServerPlayer player, CommonListenerCookie clientData, Operation<Void> original) {
		original.call(connection, player, clientData);
/*?} else {*/
/*@WrapMethod(method = "placeNewPlayer")
private void onPlayerConnect(Connection netManager, ServerPlayer player, Operation<Void> original) {
original.call(netManager, player);
*//*?}*/
		GameProfile profile = player.getGameProfile();
		String playerName = GameHelpers.getPlayerName(profile);

		if (!Common.players.containsKey(playerName)) {
			// Should not happen, but if it does it only skips the nag message, so it is not worth logging (see #292).
			return;
		}

		if (serverConfig.nagUnModdedClients && !Common.players.get(playerName)) GameHelpers.sendNag(player, serverConfig);
	}
}
