package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Opens URLs and local files in the user's browser or file manager; self-served since Minecraft 26.3 removed the Util.OS openers. */
public final class UriOpener {

	private UriOpener() {}

	public static void openUri(String uri) {
		try {
			openUri(URI.create(uri));
		} catch (IllegalArgumentException e) {
			LOGGER.error("Couldn't open uri '{}'", uri, e);
		}
	}

	public static void openUri(URI uri) {
		try {
			Process process = Runtime.getRuntime().exec(switch (PlatformUtils.operatingSystem()) {
				case WINDOWS -> new String[]{"rundll32", "url.dll,FileProtocolHandler", uri.toString()};
				case MACOS -> new String[]{"open", uri.toString()};
				default -> new String[]{"xdg-open", normalizeFileUri(uri.toString())};
			});
			process.getInputStream().close();
			process.getErrorStream().close();
			process.getOutputStream().close();
		} catch (IOException e) {
			LOGGER.error("Couldn't open location '{}'", uri, e);
		}
	}

	public static void openFile(File file) {
		openUri(file.toURI());
	}

	public static void openPath(Path path) {
		openUri(path.toUri());
	}

	// xdg-open resolves bare file: urls against the xdg prefix, so they need the host-qualified form
	private static String normalizeFileUri(String uri) {
		return uri.startsWith("file:") ? uri.replace("file:", "file://") : uri;
	}
}
