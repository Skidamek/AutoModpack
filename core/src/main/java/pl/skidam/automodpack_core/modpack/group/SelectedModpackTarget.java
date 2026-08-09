package pl.skidam.automodpack_core.modpack.group;

import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;

public record SelectedModpackTarget(
		GenerationRecord generationRecord,
		SelectionIntent expectedPriorIntent,
		ResolvedSelection selection,
		ClientPlatform platform,
		ModpackJsons.ModpackContentFields flatTarget,
		List<GenerationPatchNoteHistory.Entry> patchNotesHistory) {
	public SelectedModpackTarget {
		generationRecord = Objects.requireNonNull(generationRecord);
		selection = Objects.requireNonNull(selection);
		platform = Objects.requireNonNull(platform);
		flatTarget = Objects.requireNonNull(flatTarget);
		patchNotesHistory = List.copyOf(Objects.requireNonNull(patchNotesHistory));
		if (!GenerationTarget.from(generationRecord).equals(GenerationTarget.fromFlat(flatTarget)))
			throw new IllegalArgumentException("Selected flat target generation identity does not match complete generation record");
	}

	public GroupManifest manifest() {
		return generationRecord.manifest();
	}

	public ModpackJsons.CompleteModpackContentFields completeFields() {
		ModpackJsons.CompleteModpackContentFields fields = generationRecord.toFields();
		GenerationPatchNoteHistory.writeFields(fields, patchNotesHistory);
		return fields;
	}

	/** Returns the complete catalogue flattened for preloading, without changing the selected projection. */
	public ModpackJsons.ModpackContentFields completeTarget() {
		return SelectedTreeComposer.composeAll(manifest(), GenerationTarget.from(generationRecord));
	}

	public GenerationTarget generationTarget() {
		return GenerationTarget.from(generationRecord);
	}

	public static SelectedModpackTarget prepare(ModpackJsons.CompleteModpackContentFields fields, ClientSelectionStore store, ClientPlatform platform) {
		GenerationRecord record = GenerationRecord.fromFields(fields);
		List<GenerationPatchNoteHistory.Entry> patchNotesHistory = GenerationPatchNoteHistory.fromFields(fields);
		SelectionIntent existing = store.get(record.manifest().modpackId()).orElse(null);
		if (existing == null) return prepareResolved(record, null, GroupSelectionResolver.resolveDefault(record.manifest(), platform), platform, patchNotesHistory);
		return prepare(record, existing, existing, platform, patchNotesHistory);
	}

	public static SelectedModpackTarget prepareDefault(ModpackJsons.CompleteModpackContentFields fields, ClientPlatform platform) {
		GenerationRecord record = GenerationRecord.fromFields(fields);
		return prepareResolved(record, null, GroupSelectionResolver.resolveDefault(record.manifest(), platform), platform, GenerationPatchNoteHistory.fromFields(fields));
	}

	public static SelectedModpackTarget prepare(ModpackJsons.CompleteModpackContentFields fields, SelectionIntent expectedPriorIntent, SelectionIntent intent,
			ClientPlatform platform) {
		return prepare(GenerationRecord.fromFields(fields), expectedPriorIntent, intent, platform, GenerationPatchNoteHistory.fromFields(fields));
	}

	private static SelectedModpackTarget prepare(GenerationRecord record, SelectionIntent expectedPriorIntent, SelectionIntent intent, ClientPlatform platform) {
		return prepare(record, expectedPriorIntent, intent, platform, GenerationPatchNoteHistory.forRecord(record));
	}

	private static SelectedModpackTarget prepare(GenerationRecord record, SelectionIntent expectedPriorIntent, SelectionIntent intent, ClientPlatform platform,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory) {
		GroupManifest manifest = record.manifest();
		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, intent, platform);
		return prepareResolved(record, expectedPriorIntent, resolved, platform, patchNotesHistory);
	}

	private static SelectedModpackTarget prepareResolved(GenerationRecord record, SelectionIntent expectedPriorIntent, ResolvedSelection resolved, ClientPlatform platform,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory) {
		GroupManifest manifest = record.manifest();
		ModpackJsons.ModpackContentFields flatTarget = SelectedTreeComposer.compose(manifest, resolved, GenerationTarget.from(record));
		flatTarget.ownershipLedger = record.ownershipLedger().toFields();
		return new SelectedModpackTarget(record, expectedPriorIntent, resolved, platform, flatTarget, patchNotesHistory);
	}
}
