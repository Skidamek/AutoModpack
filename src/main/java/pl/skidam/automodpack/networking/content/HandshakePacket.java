package pl.skidam.automodpack.networking.content;

import com.google.gson.Gson;

import java.util.List;

public class HandshakePacket {
    public List<String> loaders;
    public String amVersion;
    public String mcVersion;

    public HandshakePacket(List<String> loaders, String amVersion, String mcVersion) {
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
