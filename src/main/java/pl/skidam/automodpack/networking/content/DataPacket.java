package pl.skidam.automodpack.networking.content;

import com.google.gson.Gson;
import pl.skidam.automodpack_core.auth.Secrets;

public class DataPacket {
    public String address;
    public Integer port;
    public String modpackName;
    public Secrets.Secret secret;
    public String fingerprint;
    public boolean modRequired;

    public DataPacket(String address, Integer port, String modpackName, Secrets.Secret secret, String fingerprint, boolean modRequired) {
        this.address = address;
        this.port = port;
        this.modpackName = modpackName;
        this.secret = secret;
        this.fingerprint = fingerprint;
        this.modRequired = modRequired;
    }

    public String toJson() {
        Gson gson = new Gson();
        return gson.toJson(this);
    }

    public static DataPacket fromJson(String json) {
        Gson gson = new Gson();
        return gson.fromJson(json, DataPacket.class);
    }
}
