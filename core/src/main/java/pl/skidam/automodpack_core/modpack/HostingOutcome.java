package pl.skidam.automodpack_core.modpack;

import java.util.Optional;

import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;

/**
 * An operation outcome over the modpack store. Every outcome answers which generation the host must serve:
 * committed ones carry the hosting view of their generation, every other one carries none, so binding the host
 * is a property of the outcome itself instead of a side effect remembered per code path.
 */
public interface HostingOutcome {

	/** The hosting view of the generation this outcome commits; empty when the outcome commits no generation. */
	Optional<GenerationHosting> hosted();
}
