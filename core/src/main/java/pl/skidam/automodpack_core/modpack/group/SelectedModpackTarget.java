package pl.skidam.automodpack_core.modpack.group;

import java.util.Objects;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;

public record SelectedModpackTarget(
		GenerationRecord generationRecord,
		SelectionIntent expectedPriorIntent,
		ResolvedSelection selection,
		ClientPlatform platform,
		Jsons.ModpackContentFields flatTarget) {
	public SelectedModpackTarget {
		generationRecord = Objects.requireNonNull(generationRecord);
		selection = Objects.requireNonNull(selection);
		platform = Objects.requireNonNull(platform);
		flatTarget = Objects.requireNonNull(flatTarget);
		if (!GenerationTarget.from(generationRecord.metadata()).equals(GenerationTarget.fromFlat(flatTarget)))
			throw new IllegalArgumentException("Selected flat target generation identity does not match complete generation record");
	}

	public GroupManifest manifest() {
		return generationRecord.manifest();
	}

	public Jsons.CompleteModpackContentFields completeFields() {
		return generationRecord.toFields();
	}

	public GenerationTarget generationTarget() {
		return GenerationTarget.from(generationRecord.metadata());
	}

	public static SelectedModpackTarget prepare(Jsons.CompleteModpackContentFields fields, ClientSelectionStore store, ClientPlatform platform) {
		GenerationRecord record = GenerationRecord.fromFields(fields);
		SelectionIntent existing = store.get(record.manifest().modpackId()).orElse(null);
		if (existing == null) return prepare(record, null, GroupSelectionResolver.defaultIntent(record.manifest()), platform);
		try {
			return prepare(record, existing, existing, platform);
		} catch (SelectionResolutionException e) {
			return prepare(record, existing, GroupSelectionResolver.defaultIntent(record.manifest()), platform);
		}
	}

	public static SelectedModpackTarget prepare(Jsons.CompleteModpackContentFields fields, SelectionIntent expectedPriorIntent, SelectionIntent intent,
			ClientPlatform platform) {
		return prepare(GenerationRecord.fromFields(fields), expectedPriorIntent, intent, platform);
	}

	private static SelectedModpackTarget prepare(GenerationRecord record, SelectionIntent expectedPriorIntent, SelectionIntent intent, ClientPlatform platform) {
		GroupManifest manifest = record.manifest();
		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, intent, platform);
		Jsons.ModpackContentFields flatTarget = SelectedTreeComposer.compose(manifest, resolved, GenerationTarget.from(record.metadata()));
		flatTarget.ownershipLedger = record.ownershipLedger().toFields();
		return new SelectedModpackTarget(record, expectedPriorIntent, resolved, platform, flatTarget);
	}
}
