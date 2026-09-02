package pl.skidam.automodpack_loader_core.utils;

import java.util.Locale;

public enum UpdateType {
	FULL, UPDATE, SELECT, AUTOMODPACK;

	@Override
	public String toString() {
		return name().toLowerCase(Locale.ROOT);
	}
}
