package pl.skidam.automodpack_core.modpack.group;

/** How a player receives a group: REQUIRED is always installed, RECOMMENDED arrives preselected, OPTIONAL starts unchecked. */
public enum GroupSelectionMode {
	REQUIRED,
	RECOMMENDED,
	OPTIONAL;

	/** Always installed, independent of the player's request. */
	public boolean alwaysInstalled() {
		return this == REQUIRED;
	}

	/** Arrives selected: required groups and recommended ones. */
	public boolean arrivesSelected() {
		return this != OPTIONAL;
	}

	/** The policy document's required flag. */
	public boolean policyRequired() {
		return alwaysInstalled();
	}

	/** The policy document's default-selected flag. */
	public boolean policyDefaultSelected() {
		return arrivesSelected();
	}

	/**
	 * The mode a legacy flag pair means. One combination has no mode of its own: required together with
	 * not-preselected. It collapses to REQUIRED, whose preselection is the part no player could ever act on anyway.
	 */
	public static GroupSelectionMode of(boolean required, boolean defaultSelected) {
		return required ? REQUIRED : defaultSelected ? RECOMMENDED : OPTIONAL;
	}

	/** Whether {@link #of} had to collapse such a pair, so the migration says so instead of quietly rewriting the operator's words. */
	public static boolean collapsed(boolean required, boolean defaultSelected) {
		return required && !defaultSelected;
	}
}
