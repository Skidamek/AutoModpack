package pl.skidam.automodpack_core.modpack.generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;

public final class GenerationIdentity {
	private static final String STATE_DOMAIN = "automodpack-state-v1";
	private static final String GENERATION_DOMAIN = "automodpack-generation-v1";

	private GenerationIdentity() {}

	public static String stateDigest(GroupManifest manifest) {
		Objects.requireNonNull(manifest, "manifest");
		CanonicalEncoder encoder = new CanonicalEncoder().string(STATE_DOMAIN)
				.string(manifest.modpackId()).string(manifest.modpackName()).string(manifest.automodpackVersion()).string(manifest.loader())
				.string(manifest.loaderVersion()).string(manifest.mcVersion());
		encoder.integer(manifest.groups().size());
		for (var groupEntry : manifest.groups().entrySet()) {
			GroupManifest.Group group = groupEntry.getValue();
			encoder.string(groupEntry.getKey()).string(group.displayName()).string(group.description()).string(group.tag())
					.bool(group.required()).bool(group.recommended());
			writeStrings(encoder, group.breaksWith());
			writeStrings(encoder, group.requires());
			encoder.integer(group.compatiblePlatforms().size());
			group.compatiblePlatforms().stream().map(platform -> platform.id()).sorted().forEach(encoder::string);
			encoder.integer(group.files().size());
			for (var fileEntry : group.files().entrySet()) {
				GroupManifest.GroupFile file = fileEntry.getValue();
				encoder.string(fileEntry.getKey()).longValue(file.size()).string(file.type()).bool(file.editable()).bool(file.overwriteEditable())
						.bool(file.forceCopy()).string(file.sha1()).nullableString(file.murmur());
			}
		}
		encoder.integer(manifest.selectionTags().size());
		for (var tagEntry : manifest.selectionTags().entrySet()) {
			GroupManifest.SelectionTag tag = tagEntry.getValue();
			encoder.string(tagEntry.getKey()).string(tag.displayName()).string(tag.description()).bool(tag.defaultSelected()).bool(tag.serverForced());
		}
		return sha1(encoder.bytes());
	}

	public static String generationId(int schemaVersion, String modpackId, String parentGenerationId, String createdAt, String stateDigest,
			String ledgerDigest, String patchNotesDigest, String rollbackTargetGenerationId) {
		CanonicalEncoder encoder = new CanonicalEncoder().string(GENERATION_DOMAIN).integer(schemaVersion).string(modpackId)
				.string(parentGenerationId == null ? "" : parentGenerationId).string(createdAt).string(stateDigest).string(ledgerDigest)
				.string(patchNotesDigest).string(rollbackTargetGenerationId == null ? "" : rollbackTargetGenerationId);
		return sha1(encoder.bytes());
	}

	public static String patchNotesDigest(String notes) {
		return sha1(GenerationMetadata.validateNotes(notes).getBytes(StandardCharsets.UTF_8));
	}

	private static void writeStrings(CanonicalEncoder encoder, Iterable<String> values) {
		int count = 0;
		for (String ignored : values) count++;
		encoder.integer(count);
		for (String value : values) encoder.string(value);
	}

	static String sha1Bytes(byte[] bytes) {
		return sha1(bytes);
	}

	private static String sha1(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 is unavailable", e);
		}
	}
}
