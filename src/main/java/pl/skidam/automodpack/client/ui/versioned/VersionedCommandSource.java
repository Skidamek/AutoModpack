package pl.skidam.automodpack.client.ui.versioned;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

public class VersionedCommandSource extends ServerCommandSource {


    public VersionedCommandSource(CommandOutput output, Vec3d pos, Vec2f rot, ServerWorld world, int level, String name, Text displayName, MinecraftServer server, @Nullable Entity entity) {
        super(output, pos, rot, world, level, name, displayName, server, entity);
    }

//#if MC >= 1200
   public static void sendFeedback(CommandContext<ServerCommandSource> context, Text message, boolean broadcastToOps) {
       context.getSource().sendFeedback(() -> message, broadcastToOps);
   }
//#else
//$$     public static void sendFeedback(CommandContext<ServerCommandSource> context, Text message, boolean broadcastToOps) {
//$$         context.getSource().sendFeedback(message, broadcastToOps);
//$$     }
//#endif
}
