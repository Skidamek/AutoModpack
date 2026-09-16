package pl.skidam.automodpack_core.config;

import java.net.InetSocketAddress;

import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.AddressHelpers;

public final class BootstrapConfig {

	private BootstrapConfig() {}

	public static Validated validate(ConnectionJsons.KnownHostsBootstrapFields fields) {
		if (fields == null) throw new IllegalArgumentException("Bootstrap configuration is missing");
		if (fields.origin == null || fields.origin.isBlank()) throw new IllegalArgumentException("Bootstrap origin is required");
		if (fields.fingerprint == null || fields.fingerprint.isBlank()) throw new IllegalArgumentException("Bootstrap fingerprint is required");

		InetSocketAddress origin = AddressHelpers.parseOrigin(fields.origin);
		String fingerprint = NetUtils.normalizeFingerprint(fields.fingerprint);
		boolean hasEndpoint = fields.endpoint != null && !fields.endpoint.isBlank();
		boolean hasModpackId = fields.modpackId != null && !fields.modpackId.isBlank();

		if (!hasEndpoint) {
			if (hasModpackId) throw new IllegalArgumentException("Bootstrap modpackId requires an endpoint");
			if (fields.connectionMode != null) throw new IllegalArgumentException("Bootstrap connectionMode requires an endpoint");
			return new Validated(origin, fingerprint, null, null, null);
		}

		if (!hasModpackId || !ModpackId.isValid(fields.modpackId)) throw new IllegalArgumentException("Bootstrap endpoint requires a valid modpackId");
		if (fields.connectionMode == null) throw new IllegalArgumentException("Bootstrap endpoint requires connectionMode");
		InetSocketAddress endpoint = AddressHelpers.parseEndpoint(fields.endpoint);
		return new Validated(origin, fingerprint, fields.modpackId, endpoint, fields.connectionMode);
	}

	public static ConnectionJsons.KnownHostsBootstrapFields pin(InetSocketAddress origin, String fingerprint) {
		Validated validated = validate(fields(origin, fingerprint, null, null, null));
		return fields(validated.origin(), validated.fingerprint(), null, null, null);
	}

	public static ConnectionJsons.KnownHostsBootstrapFields install(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint,
			ModpackConnectionMode connectionMode) {
		Validated validated = validate(fields(origin, fingerprint, modpackId, endpoint, connectionMode));
		return fields(validated.origin(), validated.fingerprint(), validated.modpackId(), validated.endpoint(), validated.connectionMode());
	}

	private static ConnectionJsons.KnownHostsBootstrapFields fields(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint,
			ModpackConnectionMode connectionMode) {
		return fields(AddressHelpers.formatAddress(origin), fingerprint, modpackId, endpoint == null ? null : AddressHelpers.formatAddress(endpoint), connectionMode);
	}

	private static ConnectionJsons.KnownHostsBootstrapFields fields(String origin, String fingerprint, String modpackId, String endpoint,
			ModpackConnectionMode connectionMode) {
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = origin;
		fields.fingerprint = fingerprint;
		fields.modpackId = modpackId;
		fields.endpoint = endpoint;
		fields.connectionMode = connectionMode;
		return fields;
	}

	public record Validated(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint,
			ModpackConnectionMode connectionMode) {
		public boolean installsModpack() {
			return endpoint != null;
		}
	}
}
