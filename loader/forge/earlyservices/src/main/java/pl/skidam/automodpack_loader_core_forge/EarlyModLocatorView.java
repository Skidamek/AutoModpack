package pl.skidam.automodpack_loader_core_forge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The dispatcher's compiled view of a generation's early mod locator ({@code EarlyModLocator} of
 * {@code ..._forge_40} or {@code ..._forge_47}). Every type in these signatures exists on both Forge
 * generations, so implementers load wherever either generation runs; {@link #scanMods()} is
 * {@code List<?>} because 1.18.2 fills it with {@code IModFile}s while 1.19+ fills it with
 * {@code IModLocator.ModFileOrException}s (same erasure).
 */
public interface EarlyModLocatorView {
	void initArguments(Map<String, ?> arguments);

	Stream<Path> scanCandidates();

	List<?> scanMods();
}
