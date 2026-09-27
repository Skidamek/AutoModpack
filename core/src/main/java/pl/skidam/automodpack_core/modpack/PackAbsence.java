package pl.skidam.automodpack_core.modpack;

import java.util.Objects;

/**
 * Why a server hosts no modpack. The kind is what behaviour keys off; the detail is the one wording every call site
 * renders, so the operator log, the generation command and the preview all say the same thing about the same state.
 *
 * <p>
 * Nothing here judges whether a player may join. A locally unselectable pack says nothing about what an externally
 * served endpoint is currently serving, so that verdict belongs to the client, which reads the real manifest.
 */
public record PackAbsence(Kind kind, String detail) {

	public enum Kind {
		/** The group directories and the from-server scan roots held no publishable file at all. */
		NOTHING_FOUND,
		/** Files were found and every one of them was excluded from the modpack. */
		ALL_EXCLUDED,
		/** The modpack holds files, but no group is required or selected by default, so no client would receive any. */
		NOTHING_SELECTED,
		/** Nothing was ever published and this attempt did not ask for a publication. */
		NEVER_PUBLISHED
	}

	public PackAbsence {
		kind = Objects.requireNonNull(kind, "kind");
		detail = Objects.requireNonNull(detail, "detail");
	}
}
