package pl.skidam.automodpack_core.modpack.group;

import java.util.Locale;

import com.google.gson.annotations.SerializedName;

public enum ClientPlatform {
	@SerializedName("windows")
	WINDOWS,
	@SerializedName("linux")
	LINUX,
	@SerializedName("macos")
	MACOS,
	@SerializedName("android")
	ANDROID;

	public static ClientPlatform current() {
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String javaVendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
		String javaVmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
		if (javaVendor.contains("android") || javaVmName.contains("dalvik") || javaVmName.contains("lemur")) return ANDROID;
		if (osName.contains("win")) return WINDOWS;
		if (osName.contains("mac")) return MACOS;
		return LINUX;
	}

	public static ClientPlatform parse(String value) {
		if (value == null) throw new IllegalArgumentException("Platform is null");
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "windows" -> WINDOWS;
			case "linux" -> LINUX;
			case "macos" -> MACOS;
			case "android" -> ANDROID;
			default -> throw new IllegalArgumentException("Unknown platform: " + value);
		};
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
