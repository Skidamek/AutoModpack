package pl.skidam.automodpack_core;

import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.host.HostRole;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

/**
 * What this process hosts: the server that serves the pack, the executor that builds it, and the role that gates
 * generation. The hosting entrypoints install them together and clear them on shutdown; screens and commands read them
 * while a host runs.
 */
public final class Hosting {
	private static volatile NettyServer server;
	private static volatile ModpackExecutor executor;
	private static volatile HostRole role;

	private Hosting() {}

	public static void installServer(NettyServer nettyServer) {
		server = nettyServer;
	}

	public static void installExecutor(ModpackExecutor modpackExecutor) {
		executor = modpackExecutor;
	}

	public static void installRole(HostRole hostRole) {
		role = hostRole;
	}

	/** The running host server; reading it where nothing is hosted is a bug. */
	public static NettyServer server() {
		NettyServer current = server;
		if (current == null) throw new IllegalStateException("No host server is running in this process");
		return current;
	}

	/** The running host server, or null before hosting starts and after it stops. */
	public static NettyServer serverOrNull() {
		return server;
	}

	/** The running modpack executor; reading it where nothing is hosted is a bug. */
	public static ModpackExecutor executor() {
		ModpackExecutor current = executor;
		if (current == null) throw new IllegalStateException("No modpack executor is running in this process");
		return current;
	}

	/** The executor of a host building right now, or null while no world runs. */
	public static ModpackExecutor executorOrNull() {
		return executor;
	}

	/** The host role that gates generation; reading it where nothing is hosted is a bug. */
	public static HostRole role() {
		HostRole current = role;
		if (current == null) throw new IllegalStateException("No host role is running in this process");
		return current;
	}

	/** The host role, or null before hosting starts and after it stops. */
	public static HostRole roleOrNull() {
		return role;
	}

	public static void clear() {
		server = null;
		executor = null;
		role = null;
	}
}
