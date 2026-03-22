package pl.skidam.automodpack_core.protocol;

import dev.iroh.IrohConnection;
import dev.iroh.IrohNode;
import pl.skidam.automodpack_core.protocol.iroh.AutoModpackIrohDialPlan;
import pl.skidam.automodpack_core.protocol.iroh.AutoModpackIrohDialPlanner;
import pl.skidam.automodpack_core.protocol.iroh.AutoModpackIrohNodes;
import pl.skidam.automodpack_core.protocol.iroh.IrohAvailability;
import pl.skidam.automodpack_core.protocol.iroh.IrohTransportSupport;
import pl.skidam.automodpack_core.protocol.iroh.RawTcpIrohBootstrapClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

import static pl.skidam.automodpack_core.Constants.LOGGER;

class IrohDownloadClient extends AbstractIrohDownloadClient {

    private final RawTcpIrohBootstrapClient bootstrapClient;
    private final IrohNode node;
    private final IrohConnection connection;

    IrohDownloadClient(ModpackConnectionInfo connectionInfo, int poolSize) throws IOException {
        super(poolSize);
        IrohAvailability.requireAvailable();
        if (!connectionInfo.hasEndpointId()) {
            throw new IOException("No iroh endpoint advertised by server");
        }
        if (!connectionInfo.hasRawTcpAddress() && !connectionInfo.hasDirectIpAddresses()) {
            throw new IOException("No direct IP addresses or raw TCP bootstrap address available for iroh");
        }

        IrohNode createdNode = null;
        RawTcpIrohBootstrapClient createdBootstrapClient = null;
        IrohConnection createdConnection = null;
        try {
            AutoModpackIrohDialPlan dialPlan = AutoModpackIrohDialPlanner.plan(connectionInfo);
            byte[] remoteId = dialPlan.remoteId();
            createdNode = AutoModpackIrohNodes.createDownloadNode();
            if (dialPlan.hasRawBootstrapAddress()) {
                try {
                    createdBootstrapClient = new RawTcpIrohBootstrapClient(createdNode, remoteId, dialPlan.rawBootstrapAddress());
                } catch (IOException e) {
                    if (!connectionInfo.hasDirectIpAddresses()) {
                        throw e;
                    }
                    LOGGER.warn(
                        "Optional raw TCP iroh bootstrap to {} failed, continuing with other iroh paths: {}",
                        dialPlan.rawBootstrapAddress(),
                        e.getMessage()
                    );
                    LOGGER.debug("Optional raw TCP iroh bootstrap failure", e);
                }
            }
            createdConnection = IrohTransportSupport.connectWithRetries(
                createdNode,
                dialPlan.addressBook(),
                3,
                IrohTransportSupport.CONNECT_TIMEOUT_MS
            );
            if (createdConnection == null) {
                throw new IOException("Failed to establish iroh connection");
            }
            LOGGER.info(
                "Established iroh connection to {} via {}",
                IrohTransportSupport.shortPeerId(remoteId),
                describePaths(createdConnection)
            );
        } catch (IOException e) {
            if (createdConnection != null) {
                createdConnection.abort();
            }
            if (createdBootstrapClient != null) {
                createdBootstrapClient.close();
            }
            if (createdNode != null) {
                createdNode.close();
            }
            shutdownWorkers();
            throw e;
        }
        this.node = createdNode;
        this.bootstrapClient = createdBootstrapClient;
        this.connection = createdConnection;
    }

    @Override
    protected IrohConnection requireConnection() {
        return connection;
    }

    @Override
    public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
        return IrohOperationRetrySupport.executeWithRetry(
            "Direct iroh file download",
            () -> super.downloadFile(fileHash, destination, chunkCallback)
        );
    }

    @Override
    public CompletableFuture<Path> requestRefresh(byte[][] fileHashes, Path destination) {
        return IrohOperationRetrySupport.executeWithRetry(
            "Direct iroh refresh download",
            () -> super.requestRefresh(fileHashes, destination)
        );
    }

    @Override
    public void close() {
        shutdownWorkers();
        try {
            connection.close();
        } catch (Exception e) {
            LOGGER.debug("Failed to close iroh connection", e);
        }
        if (bootstrapClient != null) {
            bootstrapClient.close();
        }
        node.close();
    }
}
