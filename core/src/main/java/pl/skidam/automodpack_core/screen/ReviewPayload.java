package pl.skidam.automodpack_core.screen;

import java.util.List;
import java.util.OptionalLong;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;

/**
 * The first-install welcome snapshot: everything the confirm screen renders, settled before the screen opens, plus the
 * {@link ReviewActions} it may drive. An empty {@code uncachedTargetBytes} means the download cost could not be
 * measured; the screen hides the stat instead of failing the review over it.
 */
public record ReviewPayload(SelectedModpackTarget target, ChangeSet catalogue, List<JournalEntry> patchNotes, String origin, List<String> unverifiedJarPaths,
		List<String> firstInstallLocalModPaths, OptionalLong uncachedTargetBytes, ReviewActions actions) {}
