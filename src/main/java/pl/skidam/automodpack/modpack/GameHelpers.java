package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;
import java.net.SocketAddress;
import java.util.UUID;

/*? if >= 1.21.9 {*/
import net.minecraft.server.players.NameAndId;
/*?}*/

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.config.ServerConfigJsons;

/*? if >=1.21.5 {*/
import java.net.URI;
/*?}*/

import static pl.skidam.automodpack.init.Common.server;
import static pl.skidam.automodpack_core.Constants.LOADER;

public class GameHelpers {

	// Simpler version of `PlayerManager.checkCanJoin`; always runs against the exact identity the login presented
	public static boolean isPlayerAuthorized(SocketAddress address, UUID playerUuid, String playerName) {
		if (server.isSameThread()) {
			return checkPlayerAuthorizedInternal(address, playerUuid, playerName);
		}

		return server.submit(() -> checkPlayerAuthorizedInternal(address, playerUuid, playerName)).join();
	}

	private static boolean checkPlayerAuthorizedInternal(SocketAddress address, UUID playerUuid, String playerName) {
		var playerManager = server.getPlayerList();
		var playerId = /*? if >= 1.21.9 {*/new NameAndId(playerUuid, playerName);/*?} else {*//*new GameProfile(playerUuid, playerName);*//*?}*/
		if (playerManager.getBans().isBanned(playerId)) {
			return false;
		}
		if (!playerManager.isWhiteListed(playerId)) {
			return false;
		}
		if (playerManager.getIpBans().isBanned(address)) {
			return false;
		}

		return true;
	}

	public static String getPlayerName(GameProfile profile) {
		/*? if >= 1.21.9 {*/
		return profile.name();
		/*?} else {*/
		/*return profile.getName();
		*//*?}*/
	}

	public static UUID getPlayerUUID(GameProfile profile) {
		/*? if >= 1.21.9 {*/
		return profile.id();
		/*?} else {*/
		/*return profile.getId();
		*//*?}*/
	}

	/** The unmodded-client nag text with the {loader} placeholder substituted; the login kick and the chat nag render the same message. */
	public static String nagMessage(ServerConfigJsons.ServerConfigFieldsV3 config) {
		return config.nagMessage.replace(ServerConfigJsons.LOADER_PLACEHOLDER, LOADER);
	}

	/** Sends the post-join chat nag: the bold nag message plus the clickable download row. */
	public static void sendNag(ServerPlayer player, ServerConfigJsons.ServerConfigFieldsV3 config) {
		Component nagText = VersionedText.literal(nagMessage(config)).withStyle(style -> style.withBold(true));
		Component nagClickableText = VersionedText.literal(config.nagClickableMessage).withStyle(style -> style.withUnderlined(true).withColor(TextColor.fromLegacyFormat(ChatFormatting.BLUE))
				/*? if >=1.21.5 {*/
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(config.nagClickableLink))));
				/*?} else {*/
				/*.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, config.nagClickableLink)));
				*//*?}*/
		/*? if >=26.1 {*/
		player.sendSystemMessage(nagText, false);
		player.sendSystemMessage(nagClickableText, false);
		/*?} else {*/
		/*player.displayClientMessage(nagText, false);
		player.displayClientMessage(nagClickableText, false);
		*//*?}*/
	}
}
