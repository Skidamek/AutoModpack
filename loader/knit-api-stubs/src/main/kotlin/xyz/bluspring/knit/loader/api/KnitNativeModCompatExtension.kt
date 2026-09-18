package xyz.bluspring.knit.loader.api

/**
 * Compile-time stub of Kilt's Knit API - never bundled, provided at runtime by Kilt.
 * Trimmed to the callback this mod implements; the runtime interface carries more
 * extension points, all defaulted.
 */
interface KnitNativeModCompatExtension {
	/**
	 * Called before mod scan begins.
	 * Primarily intended to add additional directories to scan for mods.
	 */
	fun setupModScanning(api: KnitModScanSetupApi) {}
}
