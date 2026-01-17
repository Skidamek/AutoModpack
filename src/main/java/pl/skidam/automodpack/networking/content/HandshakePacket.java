package pl.skidam.automodpack.networking.content;

import com.google.gson.Gson;

import java.util.Set;

public class HandshakePacket {
    public Set<String> loaders;
    public String amVersion;
    public String mcVersion;

    public HandshakePacket(Set<String> loaders, String amVersion, String mcVersion) {
        this.loaders = loaders;
        this.amVersion = amVersion;
        this.mcVersion = mcVersion;
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
