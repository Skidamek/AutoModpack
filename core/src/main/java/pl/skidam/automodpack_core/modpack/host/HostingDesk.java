package pl.skidam.automodpack_core.modpack.host;

import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_CERT_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_DIR;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import pl.skidam.automodpack_core.config.ServerConfigStore;
import pl.skidam.automodpack_core.loader.LoaderManagerService.EnvironmentType;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.PackAbsence;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.storage.DataRootResolver;

/** What this instance hosts when its player opens a world, read and generated from the menu while no world runs. */
public final class HostingDesk {
	private static final int HISTORY_LIMIT = 5;

	private HostingDesk() {}

	public record Generated(String modpackId, String name, String contentToken) {}

	public record Overview(HostSource nextSource, Optional<Generated> generated, List<JournalEntry> history, String fingerprint) {
		/** The suffix that lets a joiner pin this host on first contact, or null before the certificate exists. */
		public String pinSuffix() {
			return fingerprint == null ? null : "#amp1=" + fingerprint;
		}
	}

	public enum OutcomeKind {
		PUBLISHED, NO_CHANGES, PREVIEW, NOTHING, REFUSED
	}

	public record Outcome(OutcomeKind kind, String contentToken, int files, String detail) {
		static Outcome refused(String detail) {
			return new Outcome(OutcomeKind.REFUSED, null, 0, detail);
		}
	}

	public static Overview read(Path gameDirectory) throws IOException {
		HostSource next = HostRole.open(EnvironmentType.CLIENT, gameDirectory, null).source();
		GenerationStore store = generationStore(gameDirectory);
		Optional<Generated> generated = store.loadCurrent()
				.map(current -> new Generated(current.manifest().modpackId(), current.manifest().modpackName(), current.contentToken()));
		return new Overview(next, generated, generated.isPresent() ? store.history(HISTORY_LIMIT) : List.of(), fingerprint(gameDirectory));
	}

	/** Generates or previews like the world start would, under the same host role, so the menu can never do what a world may not. */
	public static Outcome generate(Path gameDirectory, boolean preview, boolean worldRunning) throws IOException {
		if (worldRunning) return Outcome.refused("A world is running; generate from it with /automodpack generate");
		ServerConfigStore.loadIfAbsent();
		ModpackExecutor executor = new ModpackExecutor(gameDirectory, HostRole.ownedElsewhere(EnvironmentType.CLIENT, gameDirectory));
		try {
			HostRole role = HostRole.open(EnvironmentType.CLIENT, gameDirectory, executor);
			Optional<String> refusal = role.generationRefusal();
			if (refusal.isPresent()) return Outcome.refused(refusal.get());
			if (preview) {
				ModpackExecutor.PreviewResult result = executor.preview();
				if (result instanceof ModpackExecutor.PreviewReady ready)
					return new Outcome(OutcomeKind.PREVIEW, ready.state().contentToken(), ready.state().summary().files(), ready.absence().map(PackAbsence::detail).orElse(""));
				return Outcome.refused(((ModpackExecutor.Rejected) result).detail());
			}
			ModpackExecutor.PublishResult result = executor.publish();
			if (result instanceof ModpackExecutor.Published published) return new Outcome(OutcomeKind.PUBLISHED, published.state().contentToken(), published.state().summary().files(), "");
			if (result instanceof ModpackExecutor.NoChanges noChanges) return new Outcome(OutcomeKind.NO_CHANGES, noChanges.state().contentToken(), noChanges.state().summary().files(), "");
			if (result instanceof ModpackExecutor.PublishResult.NothingToPublish nothing) return new Outcome(OutcomeKind.NOTHING, null, 0, nothing.absence().detail());
			return Outcome.refused(((ModpackExecutor.Rejected) result).detail());
		} finally {
			executor.stop();
		}
	}

	private static GenerationStore generationStore(Path gameDirectory) {
		DataRootResolver.Location location = DataRootResolver.resolve(gameDirectory, false);
		return new GenerationStore(gameDirectory.resolve(SERVER_DIR), location.layout().objectsDirectory(), location, null);
	}

	private static String fingerprint(Path gameDirectory) {
		Path certificate = gameDirectory.resolve(SERVER_CERT_FILE);
		if (!Files.isRegularFile(certificate)) return null;
		try {
			return NetUtils.getFingerprint(NetUtils.loadCertificate(certificate));
		} catch (Exception e) {
			return null;
		}
	}
}
