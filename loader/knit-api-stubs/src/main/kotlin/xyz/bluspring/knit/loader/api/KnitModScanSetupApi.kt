package xyz.bluspring.knit.loader.api

import java.nio.file.Path

/** Compile-time stub of Kilt's Knit API - never bundled, provided at runtime by Kilt. */
interface KnitModScanSetupApi : KnitApi {
	/**
	 * Adds a directory to scan for mods.
	 * This means the loader will attempt to load any mods in the given directory.
	 */
	fun addModDirectory(path: Path)
}
