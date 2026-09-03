package pl.skidam.automodpack_core.modpack.generation;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The identity of a selected pack state, carried by client plans and transactions. */
public record PackTarget(String modpackId, String contentToken, String policySha1, String ledgerDigest) {
	public PackTarget {
		modpackId = ModpackId.requireValid(modpackId);
		if (!HashUtils.isCanonicalSha1(contentToken)) throw new IllegalArgumentException("Invalid content token");
		if (!HashUtils.isCanonicalSha1(policySha1)) throw new IllegalArgumentException("Invalid policy document hash");
		if (!HashUtils.isCanonicalSha1(ledgerDigest)) throw new IllegalArgumentException("Invalid ledger digest");
	}

	public static PackTarget from(PackDocument document) {
		if (document == null) throw new IllegalArgumentException("Pack document is missing");
		return new PackTarget(document.manifest().modpackId(), document.contentToken(), document.policySha1(), document.ownershipLedger().digest());
	}

	public static PackTarget fromFlat(ModpackJsons.ModpackContentFields fields) {
		if (fields == null) throw new IllegalArgumentException("Selected modpack target is missing");
		String modpackId = ModpackId.requireValid(fields.modpackId);
		String ledgerDigest = OwnershipLedger.fromFields(fields.ownershipLedger).digest();
		return new PackTarget(modpackId, fields.contentToken, fields.policySha1, ledgerDigest);
	}
}
