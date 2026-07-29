package pl.skidam.automodpack_core.modpack.group;

import java.util.Objects;

import pl.skidam.automodpack_core.config.Jsons;

public record SelectedModpackTarget(
		GroupManifest manifest,
		Jsons.CompleteModpackContentFields completeFields,
		SelectionIntent expectedPriorIntent,
		ResolvedSelection selection,
		ClientPlatform platform,
		Jsons.ModpackContentFields flatTarget) {
	public SelectedModpackTarget {
		Objects.requireNonNull(manifest);
		Objects.requireNonNull(completeFields);
		Objects.requireNonNull(selection);
		Objects.requireNonNull(platform);
		Objects.requireNonNull(flatTarget);
	}

	public static SelectedModpackTarget prepare(Jsons.CompleteModpackContentFields fields, ClientSelectionStore store, ClientPlatform platform) {
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		SelectionIntent existing = store.get(manifest.modpackId()).orElse(null);
		SelectionIntent intent = existing == null ? GroupSelectionResolver.defaultIntent(manifest) : existing;
		return prepare(manifest, existing, intent, platform);
	}

	public static SelectedModpackTarget prepare(Jsons.CompleteModpackContentFields fields, SelectionIntent expectedPriorIntent, SelectionIntent intent,
			ClientPlatform platform) {
		return prepare(GroupManifestValidator.validate(fields), expectedPriorIntent, intent, platform);
	}

	private static SelectedModpackTarget prepare(GroupManifest manifest, SelectionIntent expectedPriorIntent, SelectionIntent intent, ClientPlatform platform) {
		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, intent, platform);
		Jsons.ModpackContentFields flatTarget = SelectedTreeComposer.compose(manifest, resolved);
		return new SelectedModpackTarget(manifest, manifest.toFields(), expectedPriorIntent, resolved, platform, flatTarget);
	}
}
