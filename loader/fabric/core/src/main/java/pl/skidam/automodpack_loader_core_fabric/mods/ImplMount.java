package pl.skidam.automodpack_loader_core_fabric.mods;

import java.nio.file.Path;

import pl.skidam.automodpack_core.utils.SemanticVersion;
import pl.skidam.automodpack_loader_core_fabric_15.mods.ImplMount15;
import pl.skidam.automodpack_loader_core_fabric_16.mods.ImplMount16;

/** Mounts the outer's extracted impl jar as a mod, dispatching on the running fabric-loader generation. */
public class ImplMount {

	private ImplMount() {}

	public static void mount(Path implJar, String fabricLoaderVersion) {
		try {
			if (SemanticVersion.parse(fabricLoaderVersion).compareTo(SemanticVersion.parse("0.16.1")) >= 0) {
				ImplMount16.mount(implJar);
			} else {
				ImplMount15.mount(implJar);
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to mount the AutoModpack impl jar " + implJar, e);
		}
	}
}
