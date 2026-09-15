package pl.skidam.automodpack.networking.content;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class DataPacket {
	public String endpointHost;
	public int endpointPort;
	public Secrets.Secret secret;
	public ModpackConnectionMode connectionMode;
	public boolean requireModpack;

	public DataPacket(String endpointHost, int endpointPort, Secrets.Secret secret, ModpackConnectionMode connectionMode, boolean requireModpack) {
		this.endpointHost = endpointHost;
		this.endpointPort = endpointPort;
		this.secret = secret;
		this.connectionMode = connectionMode;
		this.requireModpack = requireModpack;
	}

	public String toJson() {
		return ConfigTools.GSON.toJson(this);
	}

	public static DataPacket fromJson(String json) {
		return ConfigTools.parse(json, DataPacket.class);
	}
}
