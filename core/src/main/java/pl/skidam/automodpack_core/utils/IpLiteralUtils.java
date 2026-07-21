package pl.skidam.automodpack_core.utils;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Prevents a resolved numeric Minecraft address from gaining a reverse-DNS hostname.
 *
 * <p>
 * Adapted from Fallen-Breath/fast-ip-ping under LGPL-3.0-or-later.
 */
public final class IpLiteralUtils {
	private IpLiteralUtils() {}

	public static InetAddress preserveLiteralHost(String requestedHost, InetAddress resolved) {
		if (!isIpLiteral(requestedHost)) return resolved;
		try {
			return InetAddress.getByAddress(resolved.getHostAddress(), resolved.getAddress());
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Resolved address has invalid bytes", e);
		}
	}

	public static boolean isIpLiteral(String host) {
		if (host == null || host.isBlank()) return false;
		String candidate = host.trim();
		if (candidate.startsWith("[") && candidate.endsWith("]")) candidate = candidate.substring(1, candidate.length() - 1);
		if (isIpv4Literal(candidate)) return true;
		if (!candidate.contains(":") || !candidate.matches("[0-9A-Fa-f:.%]+")) return false;
		try {
			return InetAddress.getByName(candidate) instanceof Inet6Address;
		} catch (UnknownHostException e) {
			return false;
		}
	}

	private static boolean isIpv4Literal(String candidate) {
		String[] parts = candidate.split("\\.", -1);
		if (parts.length != 4) return false;
		for (String part : parts) {
			if (part.isEmpty() || !part.matches("\\d{1,3}")) return false;
			try {
				if (Integer.parseInt(part) > 255) return false;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return true;
	}
}
