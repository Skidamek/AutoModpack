package pl.skidam.automodpack.networking.content;

import com.google.gson.Gson;
import pl.skidam.automodpack_core.auth.Secrets;

public class DataPacket {
    public String address;
    public Integer port;
    public String modpackName;
    public Secrets.Secret secret;
    public boolean modRequired;

    public DataPacket(String address, Integer port, String modpackName, Secrets.Secret secret, boolean modRequired) {
        this.address = address;
        this.port = port;
        this.modpackName = modpackName;
        this.secret = secret;
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
