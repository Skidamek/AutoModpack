package pl.skidam.automodpack_core.config;

import java.net.InetSocketAddress;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.AddressHelpers;

public final class BootstrapConfig {
	private BootstrapConfig() {}

	public static Validated validate(ConnectionJsons.KnownHostsBootstrapFields fields) {
		if (fields == null) throw new IllegalArgumentException("Bootstrap configuration is missing");
		if (fields.origin == null || fields.origin.isBlank()) throw new IllegalArgumentException("Bootstrap origin is required");

		InetSocketAddress origin = AddressHelpers.parseOrigin(fields.origin);
		String fingerprint = fields.fingerprint == null || fields.fingerprint.isBlank() ? null : NetUtils.normalizeFingerprint(fields.fingerprint);
		boolean hasEndpoint = fields.endpoint != null && !fields.endpoint.isBlank();
		boolean hasModpackId = fields.modpackId != null && !fields.modpackId.isBlank();
		String secret = Secrets.normalizeProvisioningSecret(fields.secret);
		String serverName = normalizeServerName(fields.serverName);

		if (!hasEndpoint) {
			if (hasModpackId) throw new IllegalArgumentException("Bootstrap modpackId requires an endpoint");
			if (fields.connectionMode != null) throw new IllegalArgumentException("Bootstrap connectionMode requires an endpoint");
			if (secret != null) throw new IllegalArgumentException("Bootstrap secret requires an endpoint");
			return new Validated(origin, fingerprint, null, null, null, null, serverName);
		}

		if (!hasModpackId || !ModpackId.isValid(fields.modpackId)) throw new IllegalArgumentException("Bootstrap endpoint requires a valid modpackId");
		if (fields.connectionMode == null) throw new IllegalArgumentException("Bootstrap endpoint requires connectionMode");
		InetSocketAddress endpoint = AddressHelpers.parseEndpoint(fields.endpoint);
		return new Validated(origin, fingerprint, fields.modpackId, endpoint, fields.connectionMode, secret, serverName);
	}

	public static ConnectionJsons.KnownHostsBootstrapFields pin(InetSocketAddress origin, String fingerprint) {
		return pin(origin, fingerprint, null);
	}

	public static ConnectionJsons.KnownHostsBootstrapFields pin(InetSocketAddress origin, String fingerprint, String serverName) {
		Validated validated = validate(fields(origin, fingerprint, null, null, null, null, serverName));
		return fields(validated.origin(), validated.fingerprint(), null, null, null, null, validated.serverName());
	}

	public static ConnectionJsons.KnownHostsBootstrapFields install(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint,
			ModpackConnectionMode connectionMode, String secret) {
		return install(origin, fingerprint, modpackId, endpoint, connectionMode, secret, null);
	}

	public static ConnectionJsons.KnownHostsBootstrapFields install(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint,
			ModpackConnectionMode connectionMode, String secret, String serverName) {
		Validated validated = validate(fields(origin, fingerprint, modpackId, endpoint, connectionMode, secret, serverName));
		return fields(validated.origin(), validated.fingerprint(), validated.modpackId(), validated.endpoint(), validated.connectionMode(), validated.secret(), validated.serverName());
	}

	private static ConnectionJsons.KnownHostsBootstrapFields fields(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint,
			ModpackConnectionMode connectionMode, String secret, String serverName) {
		return fields(AddressHelpers.formatAddress(origin), fingerprint, modpackId, endpoint == null ? null : AddressHelpers.formatAddress(endpoint), connectionMode, secret, serverName);
	}

	private static ConnectionJsons.KnownHostsBootstrapFields fields(String origin, String fingerprint, String modpackId, String endpoint, ModpackConnectionMode connectionMode,
			String secret, String serverName) {
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = origin;
		fields.fingerprint = fingerprint;
		fields.modpackId = modpackId;
		fields.endpoint = endpoint;
		fields.connectionMode = connectionMode;
		fields.secret = secret;
		fields.serverName = serverName;
		return fields;
	}

	private static String normalizeServerName(String serverName) {
		if (serverName == null || serverName.isBlank()) return null;
		String trimmed = serverName.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public record Validated(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint, ModpackConnectionMode connectionMode, String secret,
			String serverName) {
		public boolean installsModpack() {
			return endpoint != null;
		}

		public boolean hasSecret() {
			return secret != null && !secret.isBlank();
		}

		public boolean hasFingerprint() {
			return fingerprint != null && !fingerprint.isBlank();
		}

		public boolean hasServerName() {
			return serverName != null && !serverName.isBlank();
		}
	}
}
