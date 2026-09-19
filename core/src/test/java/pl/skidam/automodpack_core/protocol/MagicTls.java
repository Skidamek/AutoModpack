package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMMH;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMOK;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/** Accepts one MAGIC-mode AutoModpack connection: the AMMH pre-TLS handshake, then the TLS handshake on top of it. */
final class MagicTls {
	private static final int HANDSHAKE_TIMEOUT_MILLIS = 20_000;

	private MagicTls() {}

	static SSLSocket accept(ServerSocket server, SSLContext context) throws IOException {
		Socket plain = server.accept();
		plain.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
		DataInputStream in = new DataInputStream(new BufferedInputStream(plain.getInputStream()));
		DataOutputStream out = new DataOutputStream(new BufferedOutputStream(plain.getOutputStream()));
		if (in.readInt() != MAGIC_AMMH) throw new IOException("Expected the AMMH magic");
		int hostnameLength = in.readUnsignedShort();
		in.readNBytes(hostnameLength);
		out.writeInt(MAGIC_AMOK);
		out.flush();
		SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(plain, plain.getInetAddress().getHostAddress(), plain.getPort(), true);
		tls.setUseClientMode(false);
		tls.setEnabledProtocols(new String[]{"TLSv1.3"});
		tls.startHandshake();
		return tls;
	}
}
