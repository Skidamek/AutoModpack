package pl.skidam.automodpack.client.ui.versioned;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
/*? if >= 1.21.11 {*/
import net.minecraft.server.permissions.PermissionSet;
import org.jspecify.annotations.Nullable;
/*?}*/
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class VersionedCommandSource extends CommandSourceStack {

    /*? if >=1.21.11 {*/
    public VersionedCommandSource(CommandSource commandSource, Vec3 vec3, Vec2 vec2, ServerLevel serverLevel, PermissionSet permissionSet, String string, Component component, MinecraftServer minecraftServer, @Nullable Entity entity) {
        super(commandSource, vec3, vec2, serverLevel, permissionSet, string, component, minecraftServer, entity);
    }
    /*?} else {*/
    /*public VersionedCommandSource(CommandSource output, Vec3 pos, Vec2 rot, ServerLevel world, int level, String name, Component displayName, MinecraftServer server, @org.jetbrains.annotations.Nullable Entity entity) {
        super(output, pos, rot, world, level, name, displayName, server, entity);
    }
    *//*?}*/

    public static void sendFeedback(CommandContext<CommandSourceStack> context, Component message, boolean broadcastToOps) {
    /*? if >=1.20 {*/
        context.getSource().sendSuccess(() -> message, broadcastToOps);
     /*?} else {*/
        /*context.getSource().sendSuccess(message, broadcastToOps);
    *//*?}*/
    }
}
