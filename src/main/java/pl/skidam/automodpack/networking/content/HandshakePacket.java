package pl.skidam.automodpack.networking.content;

import com.google.gson.Gson;

import java.util.Set;

public class HandshakePacket {
    public static final int CURRENT_PROTOCOL_VERSION = 2;

    public Set<String> loaders;
    public String amVersion;
    public String mcVersion;
    public int protocolVersion;
    public String requestedServerHost;
    public Integer requestedServerPort;

    public HandshakePacket(Set<String> loaders, String amVersion, String mcVersion) {
        this(loaders, amVersion, mcVersion, CURRENT_PROTOCOL_VERSION, null, null);
    }

    public HandshakePacket(Set<String> loaders, String amVersion, String mcVersion, int protocolVersion) {
        this(loaders, amVersion, mcVersion, protocolVersion, null, null);
    }

    public HandshakePacket(Set<String> loaders, String amVersion, String mcVersion, int protocolVersion, String requestedServerHost, Integer requestedServerPort) {
        this.loaders = loaders;
        this.amVersion = amVersion;
        this.mcVersion = mcVersion;
        this.protocolVersion = protocolVersion;
        this.requestedServerHost = requestedServerHost;
        this.requestedServerPort = requestedServerPort;
    }

    public String toJson() {
        Gson gson = new Gson();
        return gson.toJson(this);
    }

    public static HandshakePacket fromJson(String json) {
        Gson gson = new Gson();
        return gson.fromJson(json, HandshakePacket.class);
    }
}
