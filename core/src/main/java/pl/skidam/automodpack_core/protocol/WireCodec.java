package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import io.airlift.compress.zstd.ZstdInputStream;
import io.airlift.compress.zstd.ZstdOutputStream;

/**
 * The content codings the wire speaks, in preference order: zstd buys the best ratio at a fraction of gzip's CPU, and
 * gzip remains for middleboxes that rewrite the negotiation (proxies and CDNs re-ask in gzip) and for peers that know
 * neither. Aircompressor's lz4, lzo, and snappy streams never win a negotiation between two ends that share this
 * registry, so they are not here; the order is the negotiation preference.
 */
public enum WireCodec {
	ZSTD("zstd") {
		@Override
		public OutputStream wrap(OutputStream sink) throws IOException {
			return new ZstdOutputStream(sink);
		}

		@Override
		public InputStream unwrap(InputStream source) throws IOException {
			return new ZstdInputStream(source);
		}
	},
	GZIP("gzip") {
		@Override
		public OutputStream wrap(OutputStream sink) throws IOException {
			return new GZIPOutputStream(sink);
		}

		@Override
		public InputStream unwrap(InputStream source) throws IOException {
			return new GZIPInputStream(source);
		}
	};

	private final String wireName;

	WireCodec(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	public abstract OutputStream wrap(OutputStream sink) throws IOException;

	public abstract InputStream unwrap(InputStream source) throws IOException;

	/** The names this end offers in Accept-Encoding, most preferred first. */
	public static String offeredEncodings() {
		StringBuilder offer = new StringBuilder();
		for (WireCodec codec : values()) {
			if (offer.length() > 0) offer.append(", ");
			offer.append(codec.wireName);
		}
		return offer.toString();
	}

	/**
	 * The first registry codec the header accepts, in registry preference order; null means identity, and an unknown
	 * name stays null for the caller to reject. Tokens match exactly, and a {@code q} parameter at or below zero
	 * excludes the coding (an unparseable one counts as excluded - never send what cannot be ruled in).
	 */
	public static WireCodec negotiate(String acceptEncoding) {
		if (acceptEncoding == null) return null;
		Map<String, Double> qualities = new HashMap<>();
		for (String token : acceptEncoding.split(",")) {
			String[] parameters = token.split(";");
			String name = parameters[0].trim().toLowerCase(Locale.ROOT);
			if (name.isEmpty()) continue;
			double q = 1.0;
			for (int i = 1; i < parameters.length; i++) {
				String parameter = parameters[i].trim();
				if (!parameter.regionMatches(true, 0, "q=", 0, 2)) continue;
				try {
					q = Double.parseDouble(parameter.substring(2));
				} catch (NumberFormatException unparseable) {
					q = 0;
				}
				break;
			}
			if (q > 0) qualities.put(name, q);
		}
		for (WireCodec codec : values()) {
			if (qualities.containsKey(codec.wireName)) return codec;
		}
		return null;
	}
}
