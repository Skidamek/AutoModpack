package pl.skidam.automodpack_core.utils;

import java.util.Locale;

public class PlatformUtils {

	public static final boolean IS_MAC;
	public static final boolean IS_WIN;

	static {
		String os = System.getProperty("os.name", "generic").toLowerCase(Locale.ROOT);
		IS_MAC = os.contains("mac");
		IS_WIN = os.contains("win");
	}
}
