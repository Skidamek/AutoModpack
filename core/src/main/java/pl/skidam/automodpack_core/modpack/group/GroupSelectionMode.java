package pl.skidam.automodpack_core.modpack.group;

public enum GroupSelectionMode {
	REQUIRED,
	RECOMMENDED,
	OPTIONAL;

	// The policy document still carries the boolean pair this enum replaces on the human surface; these are its encoding.
	public boolean policyRequired() {
		return this == REQUIRED;
	}

	public boolean policyDefaultSelected() {
		return this != OPTIONAL;
	}

	public static GroupSelectionMode of(boolean required, boolean defaultSelected) {
		return required ? REQUIRED : defaultSelected ? RECOMMENDED : OPTIONAL;
	}
}
