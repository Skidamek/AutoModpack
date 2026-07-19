package pl.skidam.automodpack_core.modpack;

import java.security.SecureRandom;

public final class ModpackId {
	private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
	private static final int LENGTH = 7;
	private static final SecureRandom RANDOM = new SecureRandom();

	private ModpackId() {}

	public static String generate() {
		char[] characters = new char[LENGTH];
		for (int i = 0; i < characters.length; i++) {
			characters[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
		}
		return new String(characters);
	}

	public static boolean isValid(String modpackId) {
		if (modpackId == null || modpackId.length() != LENGTH) return false;
		for (int i = 0; i < modpackId.length(); i++) {
			char character = modpackId.charAt(i);
			if (!(character >= 'a' && character <= 'z') && !(character >= '0' && character <= '9')) return false;
		}
		return true;
	}

	public static String requireValid(String modpackId) {
		if (!isValid(modpackId)) throw new IllegalArgumentException("Invalid modpack ID: " + modpackId);
		return modpackId;
	}
}
