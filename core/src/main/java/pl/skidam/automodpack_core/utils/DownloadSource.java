package pl.skidam.automodpack_core.utils;

public record DownloadSource(String url, Provider provider) {
	public enum Provider {
		MODRINTH, CURSEFORGE
	}
}
