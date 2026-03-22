package pl.skidam.automodpack_core.protocol;

import dev.iroh.IrohPeer;
import io.netty.channel.Channel;
import pl.skidam.automodpack_core.protocol.iroh.IrohHostRuntime;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.ObservableMap;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class HybridHostServer implements ModpackHostService {
    private final NettyServer nettyFallback = new NettyServer();
    private final IrohHostRuntime irohRuntime = new IrohHostRuntime();
    private final Map<Object, String> irohBootstrapConnections = new ConcurrentHashMap<>();

    @Override
    public boolean start() {
        boolean irohStarted = irohRuntime.start();
        boolean nettyStarted = nettyFallback.start();
        return irohStarted || nettyStarted;
    }

    @Override
    public boolean stop() {
        irohBootstrapConnections.clear();
        irohRuntime.close();
        return nettyFallback.stop();
    }

    @Override
    public boolean isRunning() {
        return irohRuntime.isRunning() || nettyFallback.isRunning();
    }

    @Override
    public void setPaths(ObservableMap<String, Path> paths) {
        nettyFallback.setPaths(paths);
    }

    @Override
    public void removePaths(ObservableMap<String, Path> paths) {
        nettyFallback.removePaths(paths);
    }

    @Override
    public Optional<Path> getPath(String hash) {
        return nettyFallback.getPath(hash);
    }

    @Override
    public Map<String, Integer> getConnectionCountsByEndpoint() {
        Map<String, Integer> counts = new HashMap<>(nettyFallback.getConnectionCountsByEndpoint());
        HostConnectionStats.countValues(irohBootstrapConnections.values())
            .forEach((endpointId, count) -> counts.merge(endpointId, count, Integer::sum));
        return counts;
    }

    @Override
    public String getIrohEndpointId() {
        return irohRuntime.getEndpointId();
    }

    @Override
    public List<InetSocketAddress> getIrohDirectAddresses() {
        return irohRuntime.getDirectAddresses();
    }

    @Override
    public boolean isIrohEnabled() {
        return irohRuntime.isRunning();
    }

    @Override
    public boolean shouldHost() {
        return nettyFallback.shouldHost();
    }

    public boolean isEndpointAuthorized(byte[] peerId) {
        return irohRuntime.isEndpointAuthorized(peerId);
    }

    public IrohPeer bootstrapIrohPeer(Channel channel, byte[] peerId) {
        IrohPeer peer = bootstrapIrohPeer((Object) channel, peerId);
        if (peer != null) {
            channel.closeFuture().addListener(future -> unregisterIrohBootstrap(channel));
        }
        return peer;
    }

    public IrohPeer bootstrapIrohPeer(Object sessionKey, byte[] peerId) {
        IrohPeer peer = irohRuntime.bootstrapPeer(peerId);
        if (peer == null) {
            return null;
        }
        irohBootstrapConnections.put(sessionKey, IrohIdentity.toHex(peerId));
        return peer;
    }

    public void unregisterIrohBootstrap(Object sessionKey) {
        irohBootstrapConnections.remove(sessionKey);
    }
}
