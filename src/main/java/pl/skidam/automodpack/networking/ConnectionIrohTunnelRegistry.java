package pl.skidam.automodpack.networking;

import net.minecraft.network.Connection;
import pl.skidam.automodpack_core.protocol.iroh.tunnel.ClientConnectionIrohTunnelSession;
import pl.skidam.automodpack_core.protocol.iroh.tunnel.ServerConnectionIrohTunnelSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public final class ConnectionIrohTunnelRegistry {
    private static final Map<Connection, ClientConnectionIrohTunnelSession> CLIENT_SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Connection, ServerConnectionIrohTunnelSession> SERVER_SESSIONS = new ConcurrentHashMap<>();

    private ConnectionIrohTunnelRegistry() {
    }

    public static synchronized void registerClient(Connection connection, ClientConnectionIrohTunnelSession session) {
        ClientConnectionIrohTunnelSession previous = CLIENT_SESSIONS.put(connection, session);
        if (previous != null) {
            previous.close();
        }
        LOGGER.info("Registered connection-level client iroh tunnel session {}", session.getSessionId());
    }

    public static ClientConnectionIrohTunnelSession getClient(Connection connection) {
        return CLIENT_SESSIONS.get(connection);
    }

    public static synchronized ClientConnectionIrohTunnelSession removeClient(Connection connection) {
        ClientConnectionIrohTunnelSession session = CLIENT_SESSIONS.remove(connection);
        if (session != null) {
            LOGGER.info("Removing connection-level client iroh tunnel session {}", session.getSessionId());
            session.close();
        }
        return session;
    }

    public static synchronized void registerServer(Connection connection, ServerConnectionIrohTunnelSession session) {
        ServerConnectionIrohTunnelSession previous = SERVER_SESSIONS.put(connection, session);
        if (previous != null) {
            previous.close();
        }
        LOGGER.info("Registered connection-level server iroh tunnel session {}", session.getSessionId());
    }

    public static ServerConnectionIrohTunnelSession getServer(Connection connection) {
        return SERVER_SESSIONS.get(connection);
    }

    public static boolean hasActiveServerSession(Connection connection) {
        ServerConnectionIrohTunnelSession session = SERVER_SESSIONS.get(connection);
        return session != null && session.isActive();
    }

    public static synchronized ServerConnectionIrohTunnelSession removeServer(Connection connection) {
        ServerConnectionIrohTunnelSession session = SERVER_SESSIONS.remove(connection);
        if (session != null) {
            LOGGER.info("Removing connection-level server iroh tunnel session {}", session.getSessionId());
            session.close();
        }
        return session;
    }
}
