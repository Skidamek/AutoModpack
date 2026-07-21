package pl.skidam.automodpack_core.config;

import java.net.InetSocketAddress;

import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.AddressHelpers;

public final class BootstrapConfig {
	public static final int SCHEMA_VERSION = 1;

	private BootstrapConfig() {}

	public static Validated validate(Jsons.KnownHostsBootstrapFields fields) {
		if (fields == null) throw new IllegalArgumentException("Bootstrap configuration is missing");
		if (fields.schemaVersion != null && fields.schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported bootstrap schema version: " + fields.schemaVersion);
		if (fields.origin == null || fields.origin.isBlank()) throw new IllegalArgumentException("Bootstrap origin is required");
		if (fields.fingerprint == null || fields.fingerprint.isBlank()) throw new IllegalArgumentException("Bootstrap fingerprint is required");

		InetSocketAddress origin = AddressHelpers.parseOrigin(fields.origin);
		String fingerprint = NetUtils.normalizeFingerprint(fields.fingerprint);
		boolean hasEndpoint = fields.endpoint != null && !fields.endpoint.isBlank();
		boolean hasModpackId = fields.modpackId != null && !fields.modpackId.isBlank();

		if (!hasEndpoint) {
			if (hasModpackId) throw new IllegalArgumentException("Bootstrap modpackId requires an endpoint");
			if (fields.requiresMagic != null) throw new IllegalArgumentException("Bootstrap requiresMagic requires an endpoint");
			return new Validated(origin, fingerprint, null, null, false);
		}

		if (!hasModpackId || !ModpackId.isValid(fields.modpackId)) throw new IllegalArgumentException("Bootstrap endpoint requires a valid modpackId");
		InetSocketAddress endpoint = AddressHelpers.parseEndpoint(fields.endpoint);
		boolean requiresMagic = fields.requiresMagic == null || fields.requiresMagic;
		return new Validated(origin, fingerprint, fields.modpackId, endpoint, requiresMagic);
	}

	public static Jsons.KnownHostsBootstrapFields pin(InetSocketAddress origin, String fingerprint) {
		Validated validated = validate(fields(origin, fingerprint, null, null, null));
		return fields(validated.origin(), validated.fingerprint(), null, null, null);
	}

	public static Jsons.KnownHostsBootstrapFields install(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint,
			boolean requiresMagic) {
		Validated validated = validate(fields(origin, fingerprint, modpackId, endpoint, requiresMagic));
		return fields(validated.origin(), validated.fingerprint(), validated.modpackId(), validated.endpoint(), validated.requiresMagic());
	}

	private static Jsons.KnownHostsBootstrapFields fields(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint,
			Boolean requiresMagic) {
		return fields(AddressHelpers.formatAddress(origin), fingerprint, modpackId, endpoint == null ? null : AddressHelpers.formatAddress(endpoint), requiresMagic);
	}

	private static Jsons.KnownHostsBootstrapFields fields(String origin, String fingerprint, String modpackId, String endpoint, Boolean requiresMagic) {
		Jsons.KnownHostsBootstrapFields fields = new Jsons.KnownHostsBootstrapFields();
		fields.schemaVersion = SCHEMA_VERSION;
		fields.origin = origin;
		fields.fingerprint = fingerprint;
		fields.modpackId = modpackId;
		fields.endpoint = endpoint;
		fields.requiresMagic = requiresMagic;
		return fields;
	}

	public record Validated(InetSocketAddress origin, String fingerprint, String modpackId, InetSocketAddress endpoint, boolean requiresMagic) {
		public boolean installsModpack() {
			return endpoint != null;
		}
	}
}
