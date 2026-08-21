package pl.skidam.automodpack_core.modpack.group;

import java.util.Locale;

import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.utils.PlatformUtils;

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
		return switch (PlatformUtils.operatingSystem()) {
			case WINDOWS -> WINDOWS;
			case MACOS -> MACOS;
			case LINUX -> LINUX;
			case ANDROID -> ANDROID;
		};
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
