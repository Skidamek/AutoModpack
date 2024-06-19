package pl.skidam.automodpack.mixin.core;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
//#if MC >= 1202
import net.minecraft.server.network.ConnectedClientData;
//#endif
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.init.Common;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.GlobalVariables.serverConfig;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

//#if MC >= 1202
    @Inject(at = @At("TAIL"), method = "onPlayerConnect")
    private void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
//#else
//$$@Inject(at = @At("TAIL"), method = "onPlayerConnect")
//$$private void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, CallbackInfo ci) {
//#endif
        GameProfile profile = player.getGameProfile();
        if (!Common.players.containsKey(profile)) {
            LOGGER.error("{} isn't in the players map.", profile.getName());
            return;
        }

        if (serverConfig.nagUnModdedClients && !Common.players.get(profile)) {
            // Send chat nag message which is clickable and opens the link
            Text nagText = VersionedText.literal(serverConfig.nagMessage).styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://modrinth.com/project/automodpack")));
            // TODO check if link is clickable and if not, make it so
            player.sendMessage(nagText, false);
        }
    }
}
