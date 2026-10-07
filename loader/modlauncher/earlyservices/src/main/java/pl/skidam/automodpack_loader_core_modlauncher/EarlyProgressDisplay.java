package pl.skidam.automodpack_loader_core_modlauncher;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.lang.reflect.Method;

import pl.skidam.automodpack_core.client.LaunchProgress;

/**
 * Draws {@link LaunchProgress} on the loader's early loading window through its {@code StartupNotificationManager}. The
 * API is looked up reflectively because this class is compiled once for every ModLauncher-era loader: legacy Forge
 * before 1.20.1 has no early window at all, and NeoForge publishes the same surface under its own package. Where the
 * lookup fails, {@link #create} returns null and {@link LaunchProgress#attach} stays a no-op.
 */
public final class EarlyProgressDisplay implements LaunchProgress.Display {
	private static final String[] NOTIFICATION_MANAGERS = {"net.minecraftforge.fml.loading.progress.StartupNotificationManager",
			"net.neoforged.fml.loading.progress.StartupNotificationManager"};
	private static final int STEPS = 1000;

	private final Object meter;
	private final Method setAbsolute;
	private final Method label;
	private final Method complete;
	private final Method popBar;

	private EarlyProgressDisplay(Object meter, Method setAbsolute, Method label, Method complete, Method popBar) {
		this.meter = meter;
		this.setAbsolute = setAbsolute;
		this.label = label;
		this.complete = complete;
		this.popBar = popBar;
	}

	/** A display bound to a fresh bar on the early window, or null when this loader has no usable early window API. */
	public static EarlyProgressDisplay create(ClassLoader loader) {
		for (String name : NOTIFICATION_MANAGERS) {
			try {
				Class<?> manager = Class.forName(name, false, loader);
				Method addBar = manager.getMethod("prependProgressBar", String.class, int.class);
				Object meter = addBar.invoke(null, "AutoModpack", STEPS);
				Class<?> meterType = meter.getClass();
				return new EarlyProgressDisplay(meter, meterType.getMethod("setAbsolute", int.class), meterType.getMethod("label", String.class), meterType.getMethod("complete"),
						manager.getMethod("popBar", meterType));
			} catch (ClassNotFoundException e) {
				// Not this loader's package; try the next one.
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				LOGGER.debug("Early loading window progress is unavailable via {}", name, e);
				return null;
			}
		}
		return null;
	}

	@Override
	public void update(String text, int permille) {
		try {
			label.invoke(meter, text);
			setAbsolute.invoke(meter, permille < 0 ? 0 : Math.min(permille, STEPS));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Early progress bar rejected an update", e);
		}
	}

	@Override
	public void close() {
		try {
			complete.invoke(meter);
			popBar.invoke(null, meter);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Early progress bar could not be removed", e);
		}
	}
}
