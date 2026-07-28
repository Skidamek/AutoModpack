package pl.skidam.automodpack.networking.content;

import com.google.gson.Gson;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class DataPacket {
	private static final Gson GSON = new Gson();

	public String endpointHost;
	public int endpointPort;
	public Secrets.Secret secret;
	public boolean modRequired;
	public ModpackConnectionMode connectionMode;

	public DataPacket(String endpointHost, int endpointPort, Secrets.Secret secret, boolean modRequired, ModpackConnectionMode connectionMode) {
		this.endpointHost = endpointHost;
		this.endpointPort = endpointPort;
		this.secret = secret;
		this.modRequired = modRequired;
		this.connectionMode = connectionMode;
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static DataPacket fromJson(String json) {
		return GSON.fromJson(json, DataPacket.class);
	}
}
