package pl.skidam.automodpack.mixin.core;

import java.io.File;
import java.io.IOException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Options;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ClientOptionsPreference;

@Mixin(Options.class)
public abstract class OptionsMixin {
	@Shadow
	public abstract File getFile();

	@Inject(method = "load", at = @At("RETURN"))
	private void automodpack$loadClientPreference(CallbackInfo ci) {
		ClientOptionsPreference.load(getFile().toPath());
	}

	@Inject(method = "save", at = @At("RETURN"))
	private void automodpack$saveClientPreference(CallbackInfo ci) {
		try {
			ClientOptionsPreference.persistConfiguredFile();
		} catch (IOException e) {
			Constants.LOGGER.warn("Could not persist the AutoModpack client preference in options.txt", e);
		}
	}
}
