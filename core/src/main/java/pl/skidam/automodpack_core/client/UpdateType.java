package pl.skidam.automodpack_core.client;

import java.util.Locale;

public enum UpdateType {
	FULL, UPDATE, SELECT, AUTOMODPACK;

	@Override
	public String toString() {
		return name().toLowerCase(Locale.ROOT);
	}
}
