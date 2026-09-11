package pl.skidam.automodpack_core.modpack;

import java.util.Optional;

/**
 * An operation outcome over the modpack store. Binding the host and reporting a failed binding are properties of the
 * outcome itself instead of side effects remembered per code path; only committed outcomes carry either.
 */
public interface HostingOutcome {

	/** The failed hosting swap of a committed generation: the commit is durable, only the live host view lags behind. */
	default Optional<Throwable> hostingFailure() {
		return Optional.empty();
	}
}
