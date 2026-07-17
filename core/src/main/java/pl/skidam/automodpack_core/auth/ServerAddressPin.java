package pl.skidam.automodpack_core.auth;

import java.util.ArrayList;
import java.util.List;

import pl.skidam.automodpack_core.protocol.NetUtils;

/** Parses the one-time AutoModpack certificate pin suffix from a Minecraft address. */
public final class ServerAddressPin {
	private static final String MARKER = "#amp1=";

	private ServerAddressPin() {}

	public record Parsed(String address, String fingerprint, String error) {
		public boolean hasPin() {
			return fingerprint != null;
		}

		public boolean isMalformed() {
			return error != null;
		}
	}

	public static Parsed parse(String rawAddress) {
		if (rawAddress == null) return new Parsed(null, null, null);

		String[] parts = rawAddress.split("#", -1);
		if (parts.length == 1) return new Parsed(rawAddress, null, null);

		String minecraftAddress = parts[0].trim();
		String fingerprint = null;
		String error = null;
		boolean hasMetadata = false;
		List<String> remainingMetadata = new ArrayList<>();
		for (int i = 1; i < parts.length; i++) {
			String metadata = parts[i];
			int separator = metadata.indexOf('=');
			String key = separator < 0 ? metadata : metadata.substring(0, separator);
			if (!key.equalsIgnoreCase("amp1")) {
				remainingMetadata.add(metadata);
				continue;
			}

			hasMetadata = true;
			if (fingerprint != null || error != null || separator < 0) {
				error = "Invalid AutoModpack pinned address";
				continue;
			}
			try {
				fingerprint = NetUtils.normalizeFingerprint(metadata.substring(separator + 1));
			} catch (IllegalArgumentException e) {
				error = e.getMessage();
			}
		}

		if (!hasMetadata) return new Parsed(rawAddress, null, null);
		if (minecraftAddress.isEmpty()) error = "Invalid AutoModpack pinned address";

		String address = minecraftAddress;
		if (!remainingMetadata.isEmpty()) address += "#" + String.join("#", remainingMetadata);
		return new Parsed(address, error == null ? fingerprint : null, error);
	}

	public static String strip(String rawAddress) {
		Parsed parsed = parse(rawAddress);
		return parsed.isMalformed() ? rawAddress : parsed.address();
	}

	/** Removes AutoModpack metadata even when its value is malformed. */
	public static String sanitize(String rawAddress) {
		return parse(rawAddress).address();
	}

	public static String format(String address, String fingerprint) {
		if (address == null || address.isBlank()) throw new IllegalArgumentException("Minecraft address is invalid");
		Parsed parsed = parse(address.trim());
		if (parsed.isMalformed() || parsed.hasPin()) throw new IllegalArgumentException("Minecraft address already contains AutoModpack metadata");
		return address.trim() + MARKER + NetUtils.normalizeFingerprint(fingerprint);
	}
}
