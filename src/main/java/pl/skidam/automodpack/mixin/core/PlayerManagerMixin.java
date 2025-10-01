package pl.skidam.automodpack.mixin.core;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack.modpack.GameHelpers;

/*? if >1.20.3 {*/
import net.minecraft.server.network.CommonListenerCookie;
/*?}*/

/*? if >=1.21.5 {*/
import java.net.URI;
/*?}*/

import static pl.skidam.automodpack_core.GlobalVariables.serverConfig;

@Mixin(PlayerList.class)
public class PlayerManagerMixin {

/*? if >1.20.3 {*/
    @Inject(at = @At("TAIL"), method = "placeNewPlayer")
    private void onPlayerConnect(Connection connection, ServerPlayer player, CommonListenerCookie clientData, CallbackInfo ci) {
/*?} else {*/
/*@Inject(at = @At("TAIL"), method = "placeNewPlayer")
private void onPlayerConnect(Connection netManager, ServerPlayer player, CallbackInfo ci) {
*//*?}*/
        GameProfile profile = player.getGameProfile();
        String playerName = GameHelpers.getPlayerName(profile);

        if (!Common.players.containsKey(playerName)) {
//            LOGGER.error("{} isn't in the players map.", playerName); it should not happen but if it does then doesn't matter that much. Its only a nag message. see #292
            return;
        }

        if (serverConfig.nagUnModdedClients && !Common.players.get(playerName)) {
            // Send chat nag message which is clickable and opens the link
            Component nagText = VersionedText.literal(serverConfig.nagMessage).withStyle(style -> style.withBold(true));
            Component nagClickableText = VersionedText.literal(serverConfig.nagClickableMessage).withStyle(style -> style.withUnderlined(true).withColor(TextColor.fromLegacyFormat(ChatFormatting.BLUE))
                    /*? if >=1.21.5 {*/
                    .withClickEvent(new ClickEvent.OpenUrl(URI.create(serverConfig.nagClickableLink))));
                    /*?} else {*/
                    /*.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, serverConfig.nagClickableLink)));
                    *//*?}*/
            player.displayClientMessage(nagText, false);
            player.displayClientMessage(nagClickableText, false);
        }
    }
}
