package pl.skidam.automodpack_core.winprobe;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A separate process that holds a path with an exact Win32 share mode.
 * Java NIO always shares DELETE, so {@code held0} cannot be forced from this JVM.
 */
public final class ReplayClient implements AutoCloseable {
	private Process process;
	private BufferedWriter stdin;

	public Handle hold(Path path, Open open) {
		if (process != null) throw new WinProbeFailed("ReplayClient already holds a file");
		String script = "$p = [System.IO.File]::Open('" + psLiteral(path.toAbsolutePath().normalize().toString()) + "', [System.IO.FileMode]::Open, [System.IO.FileAccess]::" + open.access + ", [System.IO.FileShare]::"
				+ open.share + "); [Console]::Out.WriteLine('held'); [Console]::Out.Flush(); [Console]::In.ReadLine() | Out-Null; $p.Close()";
		ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-Command", script);
		builder.redirectErrorStream(true);
		try {
			process = builder.start();
			stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
			BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			String line = stdout.readLine();
			if (!"held".equals(line)) {
				process.destroyForcibly();
				process = null;
				throw new WinProbeFailed("ReplayClient did not take the hold: " + line);
			}
		} catch (WinProbeFailed failed) {
			throw failed;
		} catch (Exception error) {
			close();
			throw new WinProbeFailed("ReplayClient failed to hold " + path + ": " + error);
		}
		return new Handle(path, open);
	}

	@Override
	public void close() {
		if (process == null) return;
		try {
			if (stdin != null) {
				stdin.write("done\n");
				stdin.flush();
			}
			if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
		} catch (Exception ignored) {
			process.destroyForcibly();
		} finally {
			process = null;
			stdin = null;
		}
	}

	private static String psLiteral(String path) {
		return path.replace("'", "''");
	}

	public static final class Open {
		public static final Open SHARE_READ = new Open("Read", "Read");
		public static final Open JDK = new Open("Read", "ReadWrite,Delete");
		public static final Open EXCLUSIVE = new Open("Read", "None");

		public final String access;
		public final String share;

		public Open(String access, String share) {
			this.access = access;
			this.share = share;
		}
	}

	public static final class Handle {
		public final Path path;
		public final Open open;

		Handle(Path path, Open open) {
			this.path = path;
			this.open = open;
		}
	}
}
