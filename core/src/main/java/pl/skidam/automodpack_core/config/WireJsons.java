package pl.skidam.automodpack_core.config;

import java.util.Set;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.modpack.PackAbsence;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public final class WireJsons {
	public static final int WIRE_CONTRACT_VERSION = 1;

	private WireJsons() {}

	public static class RouteFields {
		public ModpackConnectionMode mode;
		public String host;
		public Integer port;

		public RouteFields() {}

		public RouteFields(ModpackConnectionMode mode, String host, Integer port) {
			this.mode = mode;
			this.host = host;
			this.port = port;
		}

		public static RouteFields fromServerConfig(ServerConfigJsons.ServerConfigFieldsV3 config) {
			String host = config.advertisedEndpointHost == null || config.advertisedEndpointHost.isBlank() ? null : config.advertisedEndpointHost;
			Integer port = config.advertisedEndpointPort < 1 ? null : config.advertisedEndpointPort;
			return new RouteFields(config.connectionMode, host, port);
		}
	}

	public static class TargetFields {
		public String mc;
		public String loader;
		public String loaderVersion;

		public TargetFields() {}

		public TargetFields(String mc, String loader, String loaderVersion) {
			this.mc = mc;
			this.loader = loader;
			this.loaderVersion = loaderVersion;
		}
	}

	public static class PackSummaryFields {
		public String id;
		public String name;
		public int files;
		public long bytes;

		public PackSummaryFields() {}

		public PackSummaryFields(String id, String name, int files, long bytes) {
			this.id = id;
			this.name = name;
			this.files = files;
			this.bytes = bytes;
		}
	}

	public static class ConnectDataFields {
		public int v = WIRE_CONTRACT_VERSION;
		public String amVersion;
		public String mcVersion;
		public Set<String> accepts;
		public boolean require;
		public boolean generating;
		public RouteFields route;
		public Secrets.Secret secret;

		public ConnectDataFields() {}

		public ConnectDataFields(String amVersion, String mcVersion, Set<String> accepts, boolean require, boolean generating, RouteFields route, Secrets.Secret secret) {
			this.v = WIRE_CONTRACT_VERSION;
			this.amVersion = amVersion;
			this.mcVersion = mcVersion;
			this.accepts = accepts;
			this.require = require;
			this.generating = generating;
			this.route = route;
			this.secret = secret;
		}

		public String toJson() {
			return ConfigTools.COMPACT.toJson(this);
		}

		public static ConnectDataFields fromJson(String json) {
			return ConfigTools.parse(json, ConnectDataFields.class);
		}
	}

	public static class AdvertiseFields {
		public int v = WIRE_CONTRACT_VERSION;
		public String amVersion;
		public PackSummaryFields pack;
		public PackAbsence.Kind absence;
		public TargetFields target;
		public boolean require;
		public RouteFields route;
		public String generation;

		public AdvertiseFields() {}

		public String toJson() {
			return ConfigTools.COMPACT.toJson(this);
		}

		public static AdvertiseFields fromJson(String json) {
			return ConfigTools.parse(json, AdvertiseFields.class);
		}
	}
}
