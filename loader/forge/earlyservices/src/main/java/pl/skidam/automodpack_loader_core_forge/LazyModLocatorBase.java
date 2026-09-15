package pl.skidam.automodpack_loader_core_forge;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;

/**
 * Surfaces the loader's own nested automodpack-mod.jar (META-INF/jarjar/) as a mod file, then replays
 * the early-service jars' dependency locators (see {@link EarlyServiceLayer}). Lives here because it
 * is version-agnostic; the one forgespi-seamed call, {@code createMod} - {@code Optional<IModFile>} on
 * 1.18.2, {@code IModLocator.ModFileOrException} on 1.19+ - stays in the per-version subclasses via
 * {@link #embeddedMod(Path)}, since this module compiles against a single forgespi generation for both.
 *
 * <p>
 * This class is not ServiceLoaded: the universal outer jar registers only the generation-agnostic
 * dispatchers ({@link ModLocatorDispatcher}, {@link DependencyLocatorDispatcher}), which instantiate
 * the subclass matching {@code GenerationProbes} and reach it through {@link LazyModLocatorView}.
 */
@SuppressWarnings("unused")
public abstract class LazyModLocatorBase extends AbstractJarFileDependencyLocator {
	@Override
	public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
		var list = new ArrayList<IModFile>(1);
		try {
			list.add(embeddedMod());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		for (Path jar : EarlyServiceLayer.registeredJars()) {
			EarlyServiceLayer.runDependencyLocators(jar, loadedMods, list);
		}

		return list;
	}

	@Override
	public String name() {
		return null;
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {}

	/** Extracts and returns the loader's own nested automodpack-mod.jar as a mod file. */
	private IModFile embeddedMod() throws IOException, URISyntaxException {
		// Code based on connector's
		// https://github.com/Sinytra/Connector/blob/0514fec8f189b88c5cec54dc5632fbcee13d56dc/src/main/java/dev/su5ed/sinytra/connector/locator/EmbeddedDependencies.java#L88
		final Path SELF_PATH = uncheck(() -> {
			URL jarLocation = LazyModLocatorBase.class.getProtectionDomain().getCodeSource().getLocation();
			return Path.of(jarLocation.toURI());
		});
		final String depName = "META-INF/jarjar/automodpack-mod.jar";

		final Path pathInModFile = SELF_PATH.resolve(depName);
		final URI filePathUri = new URI("jij:" + pathInModFile.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
		final Map<String, ?> outerFsArgs = ImmutableMap.of("packagePath", pathInModFile);
		final FileSystem zipFS = FileSystems.newFileSystem(filePathUri, outerFsArgs);
		final Path modPath = zipFS.getPath("/");

		return embeddedMod(modPath);
	}

	/** {@code createMod(modPath)} unwrapped - the return type is this class's only per-version seam. */
	protected abstract IModFile embeddedMod(Path modPath);
}
