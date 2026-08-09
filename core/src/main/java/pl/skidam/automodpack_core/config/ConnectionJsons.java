package pl.skidam.automodpack_core.config;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class ConnectionJsons {

	public static class ConnectionInfo {
		@SerializedName(value = "origin", alternate = "serverAddress")
		public InetSocketAddress origin; // player-entered Minecraft identity and certificate trust root
		@SerializedName(value = "endpoint", alternate = "hostAddress")
		public InetSocketAddress endpoint; // server-advertised AutoModpack route; not an authenticated identity
		public ModpackConnectionMode connectionMode;
		public transient String expectedFingerprint; // runtime-only exact certificate pin bound to origin
		public transient String trustReason; // non-null only while importing new trust

		public ConnectionInfo() {}

		public ConnectionInfo(InetSocketAddress origin, InetSocketAddress endpoint, ModpackConnectionMode connectionMode, String expectedFingerprint, String trustReason) {
			this.origin = origin;
			this.endpoint = endpoint;
			this.connectionMode = connectionMode;
			this.expectedFingerprint = expectedFingerprint;
			this.trustReason = trustReason;
		}

		public boolean isComplete() {
			return origin != null && endpoint != null && connectionMode != null && !origin.getHostString().isBlank() && !endpoint.getHostString().isBlank();
		}
	}

	public static class ConnectionRecordFields {
		public ConnectionInfo connection;
		public Map<String, Secrets.Secret> secrets = new HashMap<>();
	}

	public static class KnownHostsFields {
		public Map<String, CertificateTrustEntry> hosts = new HashMap<>();
	}

	public static class CertificateTrustEntry {
		public String fingerprint;
		public String reason;

		public CertificateTrustEntry() {}

		public CertificateTrustEntry(String fingerprint, String reason) {
			this.fingerprint = fingerprint;
			this.reason = reason;
		}
	}

	public static class KnownHostsBootstrapFields {
		public String origin;
		public String fingerprint;
		public String modpackId;
		public String endpoint;
		public ModpackConnectionMode connectionMode;
	}
}
