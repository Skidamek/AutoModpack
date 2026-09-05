package pl.skidam.automodpack_core.modpack.group;

import java.util.Objects;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;

public record SelectedModpackTarget(
		PackDocument document,
		SelectionIntent expectedPriorIntent,
		ResolvedSelection selection,
		ClientPlatform platform,
		ModpackJsons.ModpackContentFields flatTarget) {
	public SelectedModpackTarget {
		document = Objects.requireNonNull(document);
		selection = Objects.requireNonNull(selection);
		platform = Objects.requireNonNull(platform);
		flatTarget = Objects.requireNonNull(flatTarget);
		if (!PackTarget.from(document).equals(PackTarget.fromFlat(flatTarget)))
			throw new IllegalArgumentException("Selected flat target identity does not match the pack document");
	}

	public GroupManifest manifest() {
		return document.manifest();
	}

	public PackDocument document() {
		return document;
	}

	/** Returns the complete policy document flattened for preloading, without changing the selected projection. */
	public ModpackJsons.ModpackContentFields completeTarget() {
		return SelectedTreeComposer.composeAll(manifest(), PackTarget.from(document));
	}

	public PackTarget packTarget() {
		return PackTarget.from(document);
	}

	public static SelectedModpackTarget prepare(GenerationJsons.HeadDocumentFields fields, ClientSelectionStore store, ClientPlatform platform) {
		return prepare(PackDocument.fromFields(fields), store, platform);
	}

	public static SelectedModpackTarget prepare(PackDocument document, ClientSelectionStore store, ClientPlatform platform) {
		SelectionIntent existing = store.get(document.manifest().modpackId()).orElse(null);
		if (existing == null) return prepareResolved(document, null, GroupSelectionResolver.resolveDefault(document.manifest(), platform), platform);
		return prepare(document, existing, existing, platform);
	}

	public static SelectedModpackTarget prepareDefault(GenerationJsons.HeadDocumentFields fields, ClientPlatform platform) {
		return prepareDefault(PackDocument.fromFields(fields), platform);
	}

	public static SelectedModpackTarget prepareDefault(PackDocument document, ClientPlatform platform) {
		return prepareResolved(document, null, GroupSelectionResolver.resolveDefault(document.manifest(), platform), platform);
	}

	public static SelectedModpackTarget prepare(GenerationJsons.HeadDocumentFields fields, SelectionIntent expectedPriorIntent, SelectionIntent intent,
			ClientPlatform platform) {
		return prepare(PackDocument.fromFields(fields), expectedPriorIntent, intent, platform);
	}

	public static SelectedModpackTarget prepare(PackDocument document, SelectionIntent expectedPriorIntent, SelectionIntent intent, ClientPlatform platform) {
		GroupManifest manifest = document.manifest();
		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, intent, platform);
		return prepareResolved(document, expectedPriorIntent, resolved, platform);
	}

	private static SelectedModpackTarget prepareResolved(PackDocument document, SelectionIntent expectedPriorIntent, ResolvedSelection resolved, ClientPlatform platform) {
		GroupManifest manifest = document.manifest();
		ModpackJsons.ModpackContentFields flatTarget = SelectedTreeComposer.compose(manifest, resolved, PackTarget.from(document));
		flatTarget.ownershipLedger = document.ownershipLedger().toFields();
		return new SelectedModpackTarget(document, expectedPriorIntent, resolved, platform, flatTarget);
	}
}
