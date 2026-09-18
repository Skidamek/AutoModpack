package pl.skidam.automodpack.client.ui.versioned;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

public final class VersionedCommandSource {

	private VersionedCommandSource() {}

	public static void sendFeedback(CommandContext<CommandSourceStack> context, Component message, boolean broadcastToOps) {
	/*? if >=1.20 {*/
		context.getSource().sendSuccess(() -> message, broadcastToOps);
	/*?} else {*/
		/*context.getSource().sendSuccess(message, broadcastToOps);
	*//*?}*/
	}
}
