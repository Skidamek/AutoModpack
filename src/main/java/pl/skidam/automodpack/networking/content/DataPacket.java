package pl.skidam.automodpack.networking.content;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

public class DataPacket {
    public String modpackName;
    public boolean modRequired;
    public IrohAddressBookAdvertisement iroh;

    public DataPacket() {
    }

    public DataPacket(String modpackName, boolean modRequired, IrohAddressBookAdvertisement iroh) {
        this.modpackName = modpackName;
        this.modRequired = modRequired;
        this.iroh = iroh;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static DataPacket fromJson(String json) {
        return new Gson().fromJson(json, DataPacket.class);
    }

    public static class IrohAddressBookAdvertisement {
        public String endpointId;
        public List<String> directIpAddresses;
        public RawTcpRouteAdvertisement rawTcp;
        public MinecraftCarrierAdvertisement minecraft;

        public IrohAddressBookAdvertisement() {
        }

        public IrohAddressBookAdvertisement(String endpointId, List<String> directIpAddresses, RawTcpRouteAdvertisement rawTcp, MinecraftCarrierAdvertisement minecraft) {
            this.endpointId = endpointId;
            this.directIpAddresses = directIpAddresses == null ? new ArrayList<>() : new ArrayList<>(directIpAddresses);
            this.rawTcp = rawTcp;
            this.minecraft = minecraft;
        }
    }

    public static class RawTcpRouteAdvertisement {
        public String host;
        public int port;

        public RawTcpRouteAdvertisement() {
        }

        public RawTcpRouteAdvertisement(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static class MinecraftCarrierAdvertisement {
        public long tunnelSessionId;

        public MinecraftCarrierAdvertisement() {
        }

        public MinecraftCarrierAdvertisement(long tunnelSessionId) {
            this.tunnelSessionId = tunnelSessionId;
        }
    }
}
