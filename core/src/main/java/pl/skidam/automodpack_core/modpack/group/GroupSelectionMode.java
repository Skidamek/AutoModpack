package pl.skidam.automodpack_core.modpack.group;

/** How a player receives a group: REQUIRED is always installed, RECOMMENDED arrives preselected, OPTIONAL starts unchecked, AUTOMATIC installs only when required by a selected group. */
public enum GroupSelectionMode {
	REQUIRED,
	RECOMMENDED,
	OPTIONAL,
	AUTOMATIC;

	/** Always installed, independent of the player's selection. */
	public boolean alwaysInstalled() {
		return this == REQUIRED;
	}

	/** Arrives selected by default before any player customization. */
	public boolean arrivesSelected() {
		return this == REQUIRED || this == RECOMMENDED;
	}

	/** Directly toggleable or pickable by the player in the group selection UI. */
	public boolean choosable() {
		return this == RECOMMENDED || this == OPTIONAL;
	}

	/** Hidden dependency-only group installed only while a selected group requires it. */
	public boolean automatic() {
		return this == AUTOMATIC;
	}
}
