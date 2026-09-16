package pl.skidam.automodpack.networking.content;

import java.util.Set;

import pl.skidam.automodpack_core.config.ConfigTools;

public class HandshakePacket {
	public Set<String> loaders;
	public String amVersion;
	public String mcVersion;

	public HandshakePacket(Set<String> loaders, String amVersion, String mcVersion) {
		this.loaders = loaders;
		this.amVersion = amVersion;
		this.mcVersion = mcVersion;
	}

	public String toJson() {
		return ConfigTools.GSON.toJson(this);
	}

	public static HandshakePacket fromJson(String json) {
		return ConfigTools.parse(json, HandshakePacket.class);
	}
}
