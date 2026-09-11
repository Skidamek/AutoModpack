package pl.skidam.automodpack_core.modpack;

import java.util.Optional;

import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;

/**
 * An outcome that committed a generation: the host must serve its hosting view, and a failed live swap of that view is
 * reported on the commit itself instead of failing the durable commit.
 */
public interface CommittedOutcome extends HostingOutcome {

	GenerationHosting hosting();

	Throwable hostingSwapFailure();

	@Override
	default Optional<Throwable> hostingFailure() {
		return Optional.ofNullable(hostingSwapFailure());
	}

	/** The same commit carrying its failed hosting swap; the swap failure never changes what was committed. */
	CommittedOutcome withHostingFailure(Throwable failure);
}
