package pl.skidam.automodpack.client.ui.versioned;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class VersionedCommandSource extends CommandSourceStack {

    public VersionedCommandSource(CommandSource output, Vec3 pos, Vec2 rot, ServerLevel world, int level, String name, Component displayName, MinecraftServer server, @Nullable Entity entity) {
        super(output, pos, rot, world, level, name, displayName, server, entity);
    }

/*? if >=1.20 {*/
   public static void sendFeedback(CommandContext<CommandSourceStack> context, Component message, boolean broadcastToOps) {
       context.getSource().sendSuccess(() -> message, broadcastToOps);
   }
/*?} else {*/
    /*public static void sendFeedback(CommandContext<ServerCommandSource> context, Text message, boolean broadcastToOps) {
        context.getSource().sendFeedback(message, broadcastToOps);
    }
*//*?}*/
}
