package pl.skidam.automodpack.mixin;

import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;

public class MixinPlugin implements IMixinConfigPlugin {
	private static final Logger LOGGER = LogManager.getLogger(MixinPlugin.class);

	@Override
	public void onLoad(String mixinPackage) {
		MixinExtrasBootstrap.init();
		assertMainThreadSelection();
	}

	/**
	 * Mixin runs its one global config selection on whichever thread loads the first transformable class. A worker that
	 * outlives preload can be that thread, and it then hosts every mod's plugin, including ones that read game state
	 * there. Autotests fail the launch on it; a release logs it, because the game is already in that state.
	 */
	private static void assertMainThreadSelection() {
		Thread thread = Thread.currentThread();
		if (thread.getName().equals("main")) return;
		String message = "Mixin config selection ran on " + thread.getName() + " instead of main";
		if (autotest()) throw new IllegalStateException(message);
		LOGGER.warn(message);
	}

	private static boolean autotest() {
		try {
			Class.forName("pl.skidam.automodpack.client.autotest.AutoTestBridge", false, MixinPlugin.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException absent) {
			return false;
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		// Fabric-only mixins target vanilla classes that exist on every loader, so the gate is on the
		// loader being fabric, not on the target class. FabricLoginMixin is the exception: it exists
		// purely to interop with FAPI when it is co-installed and must stay live on every loader.
		if (mixinClassName.startsWith("pl.skidam.automodpack.mixin.fabric.")) {
			return isFabricLoader();
		}
		return true;
	}

	private static boolean isFabricLoader() {
		try {
			Class.forName("net.fabricmc.loader.api.FabricLoader", false, MixinPlugin.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

	}
}
