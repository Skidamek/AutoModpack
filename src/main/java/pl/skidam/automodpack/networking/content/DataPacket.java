package pl.skidam.automodpack.networking.content;

import com.google.gson.Gson;

import pl.skidam.automodpack_core.auth.Secrets;

public class DataPacket {
	public String endpointHost;
	public int endpointPort;
	public Secrets.Secret secret;
	public boolean modRequired;
	public boolean requiresMagic;

	public DataPacket(String endpointHost, int endpointPort, Secrets.Secret secret, boolean modRequired, boolean requiresMagic) {
		this.endpointHost = endpointHost;
		this.endpointPort = endpointPort;
		this.secret = secret;
		this.modRequired = modRequired;
		this.requiresMagic = requiresMagic;
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
