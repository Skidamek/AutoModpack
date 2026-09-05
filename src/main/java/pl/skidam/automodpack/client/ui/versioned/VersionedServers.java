package pl.skidam.automodpack.client.ui.versioned;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import pl.skidam.automodpack_core.utils.AddressHelpers;

/** Vanilla multiplayer server list lookups shared by every loader version. */
public final class VersionedServers {
	// The vanilla list only changes while its own screens are open, so one read per launch keeps the per-frame pack name renders cheap.
	private static final Map<String, String> ENTRY_NAMES = new ConcurrentHashMap<>();

	private VersionedServers() {}

	/** Name of the vanilla multiplayer entry serving this address, or "" when no entry with a real name matches. */
	public static String entryName(String address) {
		if (address == null || address.isBlank()) return "";
		return ENTRY_NAMES.computeIfAbsent(address, VersionedServers::lookup);
	}

	private static String lookup(String address) {
		try {
			String target = AddressHelpers.formatAddress(AddressHelpers.parseOrigin(address));
			ServerList servers = new ServerList(Minecraft.getInstance());
			servers.load();
			for (int index = 0; index < servers.size(); index++) {
				ServerData data = servers.get(index);
				if (data == null || data.ip == null || data.name == null) continue;
				if (data.name.isBlank() || data.name.toLowerCase(Locale.ROOT).equals("minecraft server")) continue;
				String entryAddress;
				try {
					entryAddress = AddressHelpers.formatAddress(AddressHelpers.parseOrigin(data.ip));
				} catch (RuntimeException ignored) {
					continue;
				}
				if (entryAddress.equals(target)) return data.name;
			}
			return "";
		} catch (RuntimeException e) {
			return "";
		}
	}
}
