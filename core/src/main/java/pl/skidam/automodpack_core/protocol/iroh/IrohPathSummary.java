package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohConnection;
import dev.iroh.IrohNode;
import dev.iroh.IrohPathInfo;

import java.util.Arrays;
import java.util.stream.Collectors;

public final class IrohPathSummary {
    private IrohPathSummary() {
    }

    public static String describe(IrohConnection connection) {
        if (connection == null) {
            return "selected=<none>; alternatives=[]";
        }

        IrohPathInfo[] paths = connection.getPaths();
        if (paths.length == 0) {
            return "selected=<none>; alternatives=[]";
        }

        String selected = Arrays.stream(paths)
            .filter(IrohPathInfo::isSelected)
            .findFirst()
            .map(IrohPathSummary::describePath)
            .orElse("<none>");

        String alternatives = Arrays.stream(paths)
            .filter(path -> !path.isSelected())
            .map(IrohPathSummary::describePath)
            .collect(Collectors.joining(", "));

        return "selected=" + selected + "; alternatives=[" + alternatives + "]";
    }

    public static String selectedOnly(IrohConnection connection) {
        if (connection == null) {
            return "<none>";
        }

        return Arrays.stream(connection.getPaths())
            .filter(IrohPathInfo::isSelected)
            .findFirst()
            .map(IrohPathSummary::describePath)
            .orElse("<none>");
    }

    public static String describePath(IrohPathInfo path) {
        String label = pathLabel(path);
        String rtt = path.getRttString();
        String address = path.getAddress() == null || path.getAddress().isBlank() ? "<unknown>" : path.getAddress();
        return label + "(addr=" + address + ", rtt=" + rtt + ")";
    }

    private static String pathLabel(IrohPathInfo path) {
        if (path == null) {
            return "unknown";
        }

        Long customTransportId = path.getCustomTransportId();
        if (customTransportId != null) {
            if (customTransportId == IrohNode.RAW_TCP_TRANSPORT_ID) {
                return "raw-tcp";
            }
            if (customTransportId == IrohNode.MINECRAFT_CONNECTION_TRANSPORT_ID) {
                return "minecraft-connection";
            }
            return "custom-" + customTransportId;
        }

        return switch (path.getKind()) {
            case IrohPathInfo.KIND_IP -> "direct-ip";
            case IrohPathInfo.KIND_RELAY -> "relay";
            case IrohPathInfo.KIND_CUSTOM -> "custom";
            default -> path.getKind().toLowerCase();
        };
    }
}
