package xyz.bluspring.knit.loader

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Compile-time stub of Kilt's Knit API (https://github.com/KiltMC/KnitLoader).
 * Provided at runtime by Kilt - these classes must never be bundled into a jar,
 * they only exist so this module can implement the API interfaces. Members are
 * trimmed to the minimum this module references.
 */
abstract class KnitModLoader<C>(val id: String, val supportedLoader: String) {
	open val modDirs: Set<Path> = setOf(Path("mods"))
}
