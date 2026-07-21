package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;

public class AddressHelpers {
	private static final int MINECRAFT_DEFAULT_PORT = 25565;

	public static String getPublicIp() {
		String[] services = {"https://ip.seeip.org/json", "https://api.ipify.org?format=json"};

		for (String service : services) {
			try {
				return Objects.requireNonNull(Json.fromUrl(service)).get("ip").getAsString();
			} catch (Exception ignored) {
				// Try next service
			}
		}

		LOGGER.error("AutoModpack couldn't get your public IP address, please configure it manually.");
		return null;
	}

	public static String getLocalIp() {
		return getNetworkIp(Inet4Address.class);
	}

	public static String getLocalIpv6() {
		return getNetworkIp(Inet6Address.class);
	}

	private static String getNetworkIp(Class<? extends InetAddress> ipClass) {
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces.hasMoreElements()) {
				NetworkInterface networkInterface = interfaces.nextElement();
				if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

				Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress addr = addresses.nextElement();
					if (ipClass.isInstance(addr) && !addr.isLinkLocalAddress()) return addr.getHostAddress().split("%")[0];
				}
			}
		} catch (SocketException e) {
			e.printStackTrace();
		}
		return null;
	}

	public static boolean areIpsEqual(String ip1, String ip2) {
		if (ip1 == null || ip2 == null) return false;
		try {
			var a1 = InetAddress.getByName(ip1);
			var a2 = InetAddress.getByName(ip2);
			return a1.equals(a2);
		} catch (UnknownHostException ignored) {
		}
		return false;
	}

	public static String normalizeIp(String ip) {
		if (ip == null) return null;

		ip = ip.trim();
		if (ip.startsWith("/")) ip = ip.substring(1);

		if (ip.contains(":")) {
			int portIndex = ip.lastIndexOf(":");
			ip = ip.substring(0, portIndex);
		}

		if (ip.startsWith("[") && ip.endsWith("]")) ip = ip.substring(1, ip.length() - 1);

		return ip;
	}

	public static InetSocketAddress format(String host, int port) {
		if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be in 0..65535");
		return InetSocketAddress.createUnresolved(normalizeHost(host), port);
	}

	public static String formatAddress(InetSocketAddress address) {
		Objects.requireNonNull(address, "address");
		InetSocketAddress normalized = format(address.getHostString(), address.getPort());
		String host = normalized.getHostString();
		if (host.contains(":")) host = "[" + host + "]";
		return host + ":" + normalized.getPort();
	}

	public static InetSocketAddress parseOrigin(String address) {
		return parseStrict(address, false);
	}

	public static InetSocketAddress parseEndpoint(String address) {
		return parseStrict(address, true);
	}

	/**
	 * @deprecated Use {@link #parseOrigin(String)} or {@link #parseEndpoint(String)} at the schema boundary.
	 */
	@Deprecated
	public static InetSocketAddress parse(String address) {
		return parseOrigin(address);
	}

	private static InetSocketAddress parseStrict(String address, boolean requireExplicitPort) {
		if (address == null) throw new IllegalArgumentException("Address is required");
		String value = address.trim();
		if (value.isEmpty()) throw new IllegalArgumentException("Address is blank");

		String host;
		Integer port = null;
		if (value.startsWith("[")) {
			int closingBracket = value.indexOf(']');
			if (closingBracket < 0) throw new IllegalArgumentException("Bracketed IPv6 address is missing ']'");
			host = value.substring(1, closingBracket);
			if (!host.contains(":")) throw new IllegalArgumentException("Brackets are only valid for IPv6 addresses");
			String remainder = value.substring(closingBracket + 1);
			if (!remainder.isEmpty()) {
				if (!remainder.startsWith(":") || remainder.length() == 1)
					throw new IllegalArgumentException("Invalid text after bracketed IPv6 address");
				port = parsePort(remainder.substring(1));
			}
		} else {
			int firstColon = value.indexOf(':');
			int lastColon = value.lastIndexOf(':');
			if (firstColon >= 0 && firstColon == lastColon) {
				host = value.substring(0, firstColon);
				port = parsePort(value.substring(firstColon + 1));
			} else {
				host = value;
			}
		}

		if (requireExplicitPort && port == null) throw new IllegalArgumentException("Endpoint must include an explicit port");
		return InetSocketAddress.createUnresolved(normalizeHost(host), port == null ? MINECRAFT_DEFAULT_PORT : port);
	}

	private static int parsePort(String port) {
		if (port.isEmpty() || !port.chars().allMatch(Character::isDigit)) throw new IllegalArgumentException("Port must be numeric");
		try {
			int parsed = Integer.parseInt(port);
			if (parsed < 1 || parsed > 65535) throw new IllegalArgumentException("Port must be in 1..65535");
			return parsed;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Port must be in 1..65535", e);
		}
	}

	private static String normalizeHost(String host) {
		if (host == null) throw new IllegalArgumentException("Host is required");
		String normalized = host.trim();
		if (normalized.isEmpty()) throw new IllegalArgumentException("Host is blank");

		if (normalized.contains(":")) return normalizeIpv6(normalized);
		if (normalized.matches("[0-9.]+")) return normalizeIpv4(normalized);

		if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
		if (normalized.isEmpty()) throw new IllegalArgumentException("Host is blank");
		try {
			return IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid DNS host: " + host, e);
		}
	}

	private static String normalizeIpv4(String host) {
		String[] parts = host.split("\\.", -1);
		if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address: " + host);
		StringBuilder normalized = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) throw new IllegalArgumentException("Invalid IPv4 address: " + host);
			int value;
			try {
				value = Integer.parseInt(part);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid IPv4 address: " + host, e);
			}
			if (value > 255) throw new IllegalArgumentException("Invalid IPv4 address: " + host);
			if (!normalized.isEmpty()) normalized.append('.');
			normalized.append(value);
		}
		return normalized.toString();
	}

	private static String normalizeIpv6(String host) {
		if (host.contains("%")) throw new IllegalArgumentException("Scoped IPv6 addresses are not supported: " + host);
		try {
			InetAddress parsed = InetAddress.getByName(host);
			if (!(parsed instanceof Inet6Address)) throw new IllegalArgumentException("Invalid IPv6 address: " + host);
			return formatIpv6(parsed.getAddress());
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Invalid IPv6 address: " + host, e);
		}
	}

	private static String formatIpv6(byte[] bytes) {
		int[] groups = new int[8];
		for (int i = 0; i < groups.length; i++) groups[i] = (Byte.toUnsignedInt(bytes[i * 2]) << 8) | Byte.toUnsignedInt(bytes[i * 2 + 1]);

		int bestStart = -1;
		int bestLength = 0;
		for (int i = 0; i < groups.length;) {
			if (groups[i] != 0) {
				i++;
				continue;
			}
			int start = i;
			while (i < groups.length && groups[i] == 0) i++;
			int length = i - start;
			if (length > bestLength && length >= 2) {
				bestStart = start;
				bestLength = length;
			}
		}

		StringBuilder result = new StringBuilder();
		for (int i = 0; i < groups.length; i++) {
			if (i == bestStart) {
				result.append("::");
				i += bestLength - 1;
				continue;
			}
			if (!result.isEmpty() && result.charAt(result.length() - 1) != ':') result.append(':');
			result.append(Integer.toHexString(groups[i]));
		}
		return result.toString();
	}

	public static boolean isLocal(String address) {
		if (address == null) return true;

		address = normalizeIp(address);
		if (address.startsWith("192.168.") || address.startsWith("127.") || address.startsWith("::1") || address.startsWith("0:0:0:0:")) return true;

		String localIp = getLocalIp();
		String localIpv6 = getLocalIpv6();
		return areIpsEqual(address, localIp) || areIpsEqual(address, localIpv6);
	}
}
