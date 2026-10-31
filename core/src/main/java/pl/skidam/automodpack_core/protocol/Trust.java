package pl.skidam.automodpack_core.protocol;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.X509ExtendedTrustManager;

import pl.skidam.automodpack_core.utils.JarUtils;

/**
 * The handle on the trust boundary. The decision lives in {@link pl.skidam.automodpack_core.protocol.trust}, loaded
 * from this jar by a class loader whose parent is the platform loader: it cannot see the game or any mod, nothing that
 * loads through the game's loader reaches it, and its own class loading never runs through the game's transformer.
 * The API crosses as JDK types only, because any type defined on both sides is two classes with one name.
 */
public final class Trust {
	private static final String BOUNDARY = "pl.skidam.automodpack_core.protocol.trust.TrustBoundary";
	private static final AtomicBoolean STARTED = new AtomicBoolean();
	private static volatile Handle handle;

	private Trust() {}

	/** Builds the boundary and loads its bundled OneCRL floor; called once, before anything decides trust. */
	public static void start() {
		if (!STARTED.compareAndSet(false, true)) return;
		OneCrl.Blocklist floor = OneCrl.loadBundledSnapshot();
		try {
			handle().snapshotHandle.invokeExact(floor.issuerSerial(), floor.spkiHex());
		} catch (Throwable failure) {
			throw failed(failure);
		}
	}

	public static boolean blocks(X509Certificate certificate) {
		try {
			return (boolean) handle().blocksHandle.invokeExact(certificate);
		} catch (Throwable failure) {
			throw failed(failure);
		}
	}

	public static String key(byte[] issuerDer, byte[] serial) {
		try {
			return (String) handle().keyHandle.invokeExact(issuerDer, serial);
		} catch (Throwable failure) {
			throw failed(failure);
		}
	}

	public static void feed(Set<String> issuerSerial, Set<String> spkiHex) {
		try {
			handle().feedHandle.invokeExact(issuerSerial, spkiHex);
		} catch (Throwable failure) {
			throw failed(failure);
		}
	}

	/** The platform CA set when {@code trustStore} is null; tests inject a keystore. */
	public static X509ExtendedTrustManager trustManager(KeyStore trustStore) {
		try {
			return (X509ExtendedTrustManager) handle().trustManagerHandle.invokeExact(trustStore);
		} catch (Throwable failure) {
			throw failed(failure);
		}
	}

	private static Handle handle() {
		Handle current = handle;
		if (current != null) return current;
		synchronized (Trust.class) {
			if (handle != null) return handle;
			handle = load();
			return handle;
		}
	}

	private static Handle load() {
		try {
			Path source = JarUtils.getJarPath(Trust.class);
			URL location = source.toUri().toURL();
			ClassLoader parent = ClassLoader.getPlatformClassLoader();
			Class<?> boundary = Class.forName(BOUNDARY, true, new URLClassLoader("AutoModpackTrust", new URL[]{location}, parent));
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			return new Handle(
					lookup.findStatic(boundary, "blocks", MethodType.methodType(boolean.class, X509Certificate.class)),
					lookup.findStatic(boundary, "key", MethodType.methodType(String.class, byte[].class, byte[].class)),
					lookup.findStatic(boundary, "feed", MethodType.methodType(void.class, Set.class, Set.class)),
					lookup.findStatic(boundary, "snapshot", MethodType.methodType(void.class, Set.class, Set.class)),
					lookup.findStatic(boundary, "trustManager", MethodType.methodType(X509ExtendedTrustManager.class, KeyStore.class)));
		} catch (Throwable failure) {
			throw new IllegalStateException("Cannot build the AutoModpack trust boundary", failure);
		}
	}

	private static RuntimeException failed(Throwable failure) {
		if (failure instanceof Error error) throw error;
		if (failure instanceof RuntimeException runtime) return runtime;
		return new IllegalStateException("The trust boundary call failed", failure);
	}

	private record Handle(MethodHandle blocksHandle, MethodHandle keyHandle, MethodHandle feedHandle, MethodHandle snapshotHandle, MethodHandle trustManagerHandle) {}
}
