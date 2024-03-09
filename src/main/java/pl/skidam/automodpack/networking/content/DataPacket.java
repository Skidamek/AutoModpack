package pl.skidam.automodpack.networking.content;

import com.google.gson.Gson;

public class DataPacket {
    public String link;
    public String modpackName;
    public boolean modRequired;

    public DataPacket(String link, String modpackName, boolean modRequired) {
        this.link = link;
        this.modpackName = modpackName;
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
