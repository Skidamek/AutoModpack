package pl.skidam.automodpack_core.modpack.group;

public enum CategoryPickMode {
	ANY,
	AT_MOST_ONE,
	EXACTLY_ONE;

	public boolean isExclusive() {
		return this != ANY;
	}

	public boolean isMandatory() {
		return this == EXACTLY_ONE;
	}
}
