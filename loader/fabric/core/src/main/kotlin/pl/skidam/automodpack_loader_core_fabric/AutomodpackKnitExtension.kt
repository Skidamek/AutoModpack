package pl.skidam.automodpack_loader_core_fabric

import net.fabricmc.loader.api.FabricLoader
import pl.skidam.automodpack_core.update.ClientStorage
import xyz.bluspring.knit.loader.api.KnitModScanSetupApi
import xyz.bluspring.knit.loader.api.KnitNativeModCompatExtension

/**
 * Hands the active projection's mods directory to Kilt's mod scan, so (Neo)Forge mods
 * synced into the projection are discovered and loaded by Kilt like mods in the standard
 * mods directory. Fabric-side mods of the projection are injected into the loader by
 * AutoModpack itself and are skipped by the scan as natively present.
 *
 * The directory is only handed over when AutoModpack's own gate says the projection would
 * load (an active state that matches the on-disk selection), so a stale, detached, or
 * unselected projection is never exposed. Kilt sees exactly the directory AutoModpack
 * would load from.
 *
 * Discovered through META-INF/services; runs inside Kilt's scan setup, before any
 * directory is walked. Without Kilt this class is never loaded.
 */
class AutomodpackKnitExtension : KnitNativeModCompatExtension {
	override fun setupModScanning(api: KnitModScanSetupApi) {
		val gameDir = FabricLoader.getInstance().gameDir
		val modsDir = ClientStorage.loadableProjectionModsDirectory(gameDir) ?: return
		api.addModDirectory(modsDir)
	}
}
