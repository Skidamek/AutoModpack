package pl.skidam.automodpack.mixin.core;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
/*? if >1.20.3 {*/
import net.minecraft.server.network.ConnectedClientData;
/*?}*/
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.init.Common;

/*? if >1.21.4 {*/
/*import java.net.URI;
*//*?}*/

import static pl.skidam.automodpack_core.GlobalVariables.serverConfig;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

/*? if >1.20.3 {*/
    @Inject(at = @At("TAIL"), method = "onPlayerConnect")
    private void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
/*?} else {*/
/*@Inject(at = @At("TAIL"), method = "onPlayerConnect")
private void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, CallbackInfo ci) {
*//*?}*/
        GameProfile profile = player.getGameProfile();
        String playerName = profile.getName();

        if (!Common.players.containsKey(playerName)) {
//            LOGGER.error("{} isn't in the players map.", playerName); it should not happen but if it does then doesn't matter that much. Its only a nag message. see #292
            return;
        }

        if (serverConfig.nagUnModdedClients && !Common.players.get(playerName)) {
            // Send chat nag message which is clickable and opens the link
            Text nagText = VersionedText.literal(serverConfig.nagMessage).styled(style -> style.withBold(true));
            Text nagClickableText = VersionedText.literal(serverConfig.nagClickableMessage).styled(style -> style.withUnderline(true).withColor(TextColor.fromFormatting(Formatting.BLUE))
                    /*? if >1.21.4 {*/
                    /*.withClickEvent(new ClickEvent.OpenUrl(URI.create(serverConfig.nagClickableLink))));
                    *//*?} else {*/
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, serverConfig.nagClickableLink)));
                    /*?}*/
            player.sendMessage(nagText, false);
            player.sendMessage(nagClickableText, false);
        }
    }
}
