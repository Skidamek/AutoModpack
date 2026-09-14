package pl.skidam.automodpack.mixin.fabric;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
/*? if >=1.19.2 {*/
import net.minecraft.commands.CommandBuildContext;
/*?}*/

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static pl.skidam.automodpack.modpack.Commands.register;

/** Registers the automodpack commands on fabric, which ships without FAPI's CommandRegistrationCallback;
 * the constructor has a single return on every target, so RETURN is the tail. Forge and neoforge register
 * through RegisterCommandsEvent instead and the plugin skips this mixin there.
 */
@Mixin(Commands.class)
public class CommandsMixin {
	@Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;

	@Inject(method = "<init>", at = @At("RETURN"))
	/*? if <1.19.2 {*/
	/*private void onConstruct(Commands.CommandSelection selection, CallbackInfo ci) {
	*//*?} else {*/
	private void onConstruct(Commands.CommandSelection selection, CommandBuildContext buildContext, CallbackInfo ci) {
	/*?}*/
		register(this.dispatcher);
	}
}
