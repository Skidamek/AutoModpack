package pl.skidam.automodpack.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
/*? if >=1.20.4 {*/
/*?}*/
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
/*? if >=1.21.6 {*/
import net.minecraft.client.gui.screens.GenericMessageScreen;
/*?}*/
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedToasts;
import pl.skidam.automodpack_core.text.L10n;
import pl.skidam.automodpack.client.ui.widget.CheckboxWidget;
import pl.skidam.automodpack.client.ui.widget.RowListWidget;
import pl.skidam.automodpack.client.ui.widget.RowViewport;
/*? if >= 1.21.10 {*/
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
/*?}*/
/*? if >= 1.20.5 {*/
import net.minecraft.client.multiplayer.TransferState;
/*?}*/
import net.minecraft.network.chat.Component;
/*? if >= 1.19.2 {*/
import net.minecraft.network.chat.contents.TranslatableContents;
/*?} else {*/
/*import net.minecraft.network.chat.TranslatableComponent;
*//*?}*/

import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import pl.skidam.automodpack_core.utils.ScreenshotSettler;

public final class AutoTestBridge {
	private static final AtomicBoolean STARTED = new AtomicBoolean(false);
	private static volatile Path bridgeDir;
	private static final AtomicBoolean CLIENT_READY = new AtomicBoolean(false);
	private static final AtomicBoolean RELOAD_FINISHED = new AtomicBoolean(false);
	private static final Object READY_STATE_LOCK = new Object();
	private static final AtomicBoolean READY_STATE_PUBLISHED = new AtomicBoolean(false);
	private static final AtomicBoolean READY_STATE_WRITE_FAILED = new AtomicBoolean(false);
	private static final AtomicReference<PendingScreenshot> PENDING_SCREENSHOT = new AtomicReference<>();
	private static final long SCREENSHOT_SETTLE_TIMEOUT_SECONDS = 30;

	public static void markReloadFinished() {
		RELOAD_FINISHED.set(true);
		onClientReady();
	}

	private static boolean hasReloadFinished() {
		return RELOAD_FINISHED.get();
	}

	public static void start() {
		if (!STARTED.compareAndSet(false, true)) return;
		String token = System.getProperty("automodpack.autotest.token", "");
		String gameDir = System.getProperty("automodpack.autotest.gamedir", "");
		if (token.isBlank() || gameDir.isBlank()) {
			LOGGER.warn("AutoModpack bridge disabled: token is '{}', gamedir is '{}'", token, gameDir);
			return;
		}

		Thread t = new Thread(() -> run(Path.of(gameDir), token), "AutoModpackBridge");
		t.setDaemon(true);
		t.start();

		// Fallback in case the reload-finished mixin fires before the title screen is shown.
		Thread waiter = new Thread(() -> {
			while (!CLIENT_READY.get()) {
				try {
					Thread.sleep(100);
					if (currentScreen() instanceof TitleScreen && hasReloadFinished()) {
						onClientReady();
						return;
					}
				} catch (Exception ignored) {
				}
			}
		}, "AutoModpackReadyWaiter");
		waiter.setDaemon(true);
		waiter.start();
	}

	public static void onClientReady() {
		CLIENT_READY.set(true);
		publishReadyState();
	}

	private static void publishReadyState() {
		if (!CLIENT_READY.get()) return;
		synchronized (READY_STATE_LOCK) {
			if (READY_STATE_PUBLISHED.get()) return;
			Path dir = bridgeDir;
			if (dir == null) return;
			try {
				writeFile(dir.resolve("bridge-state.json"), "{\"status\":\"ready\"}");
				READY_STATE_PUBLISHED.set(true);
				READY_STATE_WRITE_FAILED.set(false);
				LOGGER.info("AutoModpack autotest: client ready, wrote bridge-state.json");
			} catch (IOException e) {
				if (READY_STATE_WRITE_FAILED.compareAndSet(false, true)) LOGGER.error("Cannot write client-ready state", e);
			}
		}
	}

	private static void run(Path gameDir, String token) {
		Path dir = gameDir.resolve("automodpack/autotest");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			LOGGER.error("Cannot initialize autotest bridge directory", e);
			return;
		}

		bridgeDir = dir;
		LOGGER.info("AutoModpack bridge ready at {}", dir);
		publishReadyState();
		Path cmd = dir.resolve("bridge-command.json");
		Path rsp = dir.resolve("bridge-response.json");
		while (true) {
			try {
				if (CLIENT_READY.get() && !READY_STATE_PUBLISHED.get()) publishReadyState();
				if (Files.exists(cmd)) {
					String json = Files.readString(cmd, StandardCharsets.UTF_8);
					Files.delete(cmd);
					writeFile(rsp, handle(json, token));
				}
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				LOGGER.error("AutoModpack bridge error", e);
			}
		}
	}

	private static String handle(String json, String token) {
		try {
			JsonObject req = JsonParser.parseString(json).getAsJsonObject();
			if (!token.equals(optString(req, "token"))) {
				return err("invalid bridge token");
			}

			return exec(req);
		} catch (Exception e) {
			LOGGER.error("AutoModpack bridge command failed", e);
			return err(e.getMessage());
		}
	}

	private static String exec(JsonObject req) throws Exception {
		return switch (optString(req, "op")) {
			case "ping" -> ok();
			case "gui" -> onMain(() -> gui().toString());
			case "click" -> onMain(() -> click(req));
			case "text" -> onMain(() -> text(req));
			case "screenshot" -> screenshot(req);
			case "connect" -> onMain(() -> connect(req));
			case "disconnect" -> onMain(AutoTestBridge::disconnect);
			case "quit" -> onMain(AutoTestBridge::quit);
			default -> err("unknown operation: " + optString(req, "op"));
		};
	}

	private static Screen currentScreen() {
		return ScreenImpl.currentScreen();
	}

	private static JsonObject gui() {
		Screen s = currentScreen();
		JsonObject o = base();
		o.addProperty("screenClass", s == null ? null : s.getClass().getName());
		o.addProperty("title", s == null ? null : s.getTitle().getString());
		o.addProperty("screenRevision", screenRevision(s));
		GuiElements elements = elements(s);
		o.add("buttons", elementsJson(elements.buttons()));
		o.add("textFields", elementsJson(elements.textFields()));
		o.add("other", elementsJson(elements.other()));
		o.add("elements", elementsJson(elements.all()));
		JsonArray toasts = new JsonArray();
		for (VersionedToasts.Shown toast : VersionedToasts.shown()) {
			JsonObject shown = new JsonObject();
			shown.addProperty("title", toast.title());
			shown.addProperty("description", toast.description());
			toasts.add(shown);
		}
		o.add("toasts", toasts);
		return o;
	}

	private static String click(JsonObject req) {
		Screen s = currentScreen();
		if (s == null) return err("no screen");
		if (screenRevision(s) != optLong(req, "screenRevision", -1)) return err("stale_screen", "GUI screen changed before click");

		int button = optInt(req, "button", 0);
		int x;
		int y;
		if (has(req, "id")) {
			GuiElement e = elements(s).byId(optInt(req, "id", -1));
			if (e == null) return err("no gui element with id " + optInt(req, "id", -1));
			if (has(req, "enable") && req.get("enable").getAsBoolean() && e.widget() instanceof Button) {
				e.widget().active = true;
			}
			int[] point = e.clickPoint();
			x = point[0];
			y = point[1];
		} else {
			x = optInt(req, "x", -1);
			y = optInt(req, "y", -1);
			if (x < 0 || y < 0) return err("click needs either id or x/y");
		}

		s.mouseMoved(x, y);
		/*? if >= 26.3 {*/
		/*if (button == 0) button = 1;
		*//*?}*/
		/*? if >= 1.21.10 {*/
		MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
		s.mouseClicked(event, false);
		s.mouseReleased(event);
		/*?} else {*/
		/*s.mouseClicked(x, y, button);
		s.mouseReleased(x, y, button);
		*//*?}*/
		return ok();
	}

	private static String text(JsonObject req) {
		Screen s = currentScreen();
		if (s == null) return err("no screen");
		if (screenRevision(s) != optLong(req, "screenRevision", -1)) return err("stale_screen", "GUI screen changed before text input");

		int id = optInt(req, "id", -1);
		GuiElement e = elements(s).byId(id);
		if (e == null || !(e.widget() instanceof EditBox editBox)) {
			return err("no text field with id " + id);
		}

		editBox.setValue(optString(req, "text"));
		return ok();
	}

	private static String screenshot(JsonObject req) throws Exception {
		if (!Boolean.getBoolean("automodpack.autotest.render")) {
			throw new IOException("screenshots require a scenario with renderClient: true");
		}
		Path dir = bridgeDir;
		if (dir == null) throw new IOException("bridge directory is unavailable");
		String name = screenshotName(optString(req, "name"));
		Path screenshots = dir.resolve("screenshots");
		Files.createDirectories(screenshots);
		Path path = screenshots.resolve(name + ".png");
		CompletableFuture<String> captured = new CompletableFuture<>();
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> queueScreenshot(captured, path));
		try {
			return captured.get(SCREENSHOT_SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			PendingScreenshot pending = PENDING_SCREENSHOT.get();
			if (pending != null && pending.captured() == captured && PENDING_SCREENSHOT.compareAndSet(pending, null)) pending.logTimeout();
			throw new IOException("screenshot did not settle within " + SCREENSHOT_SETTLE_TIMEOUT_SECONDS + " seconds", e);
		}
	}

	private static void queueScreenshot(CompletableFuture<String> captured, Path path) {
		Screen targetScreen = currentScreen();
		if (targetScreen == null) {
			LOGGER.error("AutoModpack autotest screenshot {} rejected: there is no active screen", path.getFileName());
			captured.completeExceptionally(new IOException("cannot capture a screenshot without an active screen"));
			return;
		}
		if (!PENDING_SCREENSHOT.compareAndSet(null, new PendingScreenshot(captured, path, targetScreen))) {
			captured.completeExceptionally(new IOException("another screenshot is already pending"));
		}
	}

	public static void onFrameRendered() {
		PendingScreenshot pending = PENDING_SCREENSHOT.get();
		if (pending == null) return;
		ScreenshotSettler.Frame frame = captureFrame();
		ScreenshotSettler.Observation observation = pending.observe(frame);
		if (observation == ScreenshotSettler.Observation.WAIT) return;
		if (!PENDING_SCREENSHOT.compareAndSet(pending, null)) return;
		if (observation == ScreenshotSettler.Observation.TARGET_GONE) {
			LOGGER.info("AutoModpack autotest screenshot {} skipped: {}", pending.path().getFileName(), describe(pending.targetScreen(), frame));
			pending.captured().complete(pending.skippedResponse(frame).toString());
			return;
		}
		try {
			Minecraft minecraft = Minecraft.getInstance();
			/*? if >=26.2 {*/
			Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), 1, image -> completeScreenshot(pending.captured(), pending.path(), image));
		/*?} else if >=1.21.6 {*/
			/*Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), 1, image -> completeScreenshot(pending.captured(), pending.path(), image));
			*//*?} else if >=1.21.5 {*/
			/*Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> completeScreenshot(pending.captured(), pending.path(), image));
			*//*?} else {*/
			/*completeScreenshot(pending.captured(), pending.path(), Screenshot.takeScreenshot(minecraft.getMainRenderTarget()));
			*//*?}*/
		} catch (Exception e) {
			pending.captured().completeExceptionally(e);
		}
	}

	private static final class PendingScreenshot {
		private final CompletableFuture<String> captured;
		private final Path path;
		private final Screen targetScreen;
		private final ScreenshotSettler settler;
		private volatile ScreenshotSettler.Frame lastFrame;

		private PendingScreenshot(CompletableFuture<String> captured, Path path, Screen targetScreen) {
			this.captured = captured;
			this.path = path;
			this.targetScreen = targetScreen;
			this.settler = new ScreenshotSettler(targetScreen);
		}

		private ScreenshotSettler.Observation observe(ScreenshotSettler.Frame frame) {
			lastFrame = frame;
			return settler.observe(frame);
		}

		private JsonObject skippedResponse(ScreenshotSettler.Frame frame) {
			JsonObject response = base();
			response.addProperty("skipped", true);
			response.addProperty("reason", describe(targetScreen, frame));
			return response;
		}

		private void logTimeout() {
			ScreenshotSettler.Frame frame = lastFrame;
			LOGGER.error("AutoModpack autotest screenshot {} did not settle: {}", path.getFileName(), frame == null ? "no rendered frame observed" : describe(targetScreen, frame));
		}

		private CompletableFuture<String> captured() {
			return captured;
		}

		private Path path() {
			return path;
		}

		private Screen targetScreen() {
			return targetScreen;
		}
	}

	private static ScreenshotSettler.Frame captureFrame() {
		Minecraft minecraft = Minecraft.getInstance();
		/*? if >=26.2 {*/
		// Since 26.2 Gui owns the render overlay; sample it after GameRenderer rendered the frame.
		return new ScreenshotSettler.Frame(currentScreen(), minecraft.gui.overlay() != null);
		/*?} else {*/
		/*return new ScreenshotSettler.Frame(currentScreen(), minecraft.getOverlay() != null);
		*//*?}*/
	}

	private static String describe(Screen targetScreen, ScreenshotSettler.Frame frame) {
		return "targetScreen=" + targetScreen.getClass().getName() + ", renderedScreen=" + (frame.screen() == null ? "<none>" : frame.screen().getClass().getName()) + ", overlayVisible=" + frame.overlayVisible();
	}

	private static void completeScreenshot(CompletableFuture<String> captured, Path path, NativeImage source) {
		try (source) {
			BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, source.getWidth(), source.getHeight(), source.makePixelArray(), 0, source.getWidth());
			if (!ImageIO.write(image, "png", path.toFile())) throw new IOException("Java PNG encoder is unavailable");
			JsonObject response = base();
			response.addProperty("path", "automodpack/autotest/screenshots/" + path.getFileName());
			response.addProperty("width", source.getWidth());
			response.addProperty("height", source.getHeight());
			captured.complete(response.toString());
		} catch (Exception e) {
			captured.completeExceptionally(e);
		}
	}

	private static String screenshotName(String requested) {
		String source = requested.isBlank() ? "screen" : requested;
		StringBuilder safe = new StringBuilder();
		for (int index = 0; index < source.length(); index++) {
			char character = source.charAt(index);
			if (Character.isLetterOrDigit(character) || character == '-' || character == '_') safe.append(character);
			else if (safe.length() > 0 && safe.charAt(safe.length() - 1) != '-') safe.append('-');
		}
		return safe.length() == 0 ? "screen" : safe.toString();
	}

	private static String connect(JsonObject req) {
		Minecraft c = Minecraft.getInstance();
		String host = optString(req, "host");
		if (host.isBlank()) return err("host is required");

		ServerAddress address = ServerAddress.parseString(host);
		ServerData serverData = new ServerData("AutoTest", host
				/*? if >= 1.20.4 {*/, ServerData.Type.OTHER/*?} else {*//*, false*//*?}*/);
		/*? if >= 1.20.5 {*/
		ConnectScreen.startConnecting(new TitleScreen(), c, address, serverData, false, (TransferState) null);
		/*?} else if >= 1.20.4 {*/
		/*ConnectScreen.startConnecting(new TitleScreen(), c, address, serverData, false);
		*//*?} else if >= 1.20.1 {*/
		/*ConnectScreen.startConnecting(new TitleScreen(), c, address, serverData, false);
		*//*?} else {*/
		/*ConnectScreen.startConnecting(new TitleScreen(), c, address, serverData);
		*//*?}*/
		return ok();
	}

	private static String disconnect() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			ScreenImpl.setScreen(new TitleScreen());
			return ok();
		}

		/*? if >=1.21.6 {*/
		minecraft.level.disconnect(translatable("multiplayer.status.quitting"));
		minecraft.clearClientLevel(new GenericMessageScreen(translatable("multiplayer.disconnect.generic")));
		ScreenImpl.setScreen(new TitleScreen());
		/*?} else if >=1.21.1 {*/
		/*minecraft.disconnect(new TitleScreen());
		*//*?} else {*/
		/*minecraft.level.disconnect();
		minecraft.clearLevel(new TitleScreen());
		*//*?}*/
		return ok();
	}

	private static String quit() {
		Minecraft.getInstance().stop();
		return ok();
	}

	private static GuiElements elements(Screen screen) {
		if (screen == null) return new GuiElements(List.of());

		// children() (and nested ContainerEventHandler children) is what rebuildWidgets replaces.
		// Field scraping used to also pick up Screen.focused, which keeps the pre-rebuild widget with
		// stale text and visible=true — a ghost that could satisfy wait_for/no_element.
		LinkedHashSet<AbstractWidget> widgets = new LinkedHashSet<>();
		List<RowViewport> rowViewports = new ArrayList<>();
		for (GuiEventListener child : screen.children()) collectAttachedWidgets(child, widgets, rowViewports);
		// Dropdown menus are not screen children (the frame renders them in its overlay pass), so their rows are
		// collected through the screen itself.
		if (screen instanceof VersionedScreen versioned) {
			for (RowListWidget menu : versioned.openMenus()) rowViewports.add(menu);
		}

		List<GuiElement> result = new ArrayList<>();
		int id = 0;
		for (AbstractWidget widget : widgets) result.add(GuiElement.of(id++, widget));
		// Scrollable lists are represented by one clickable element per row; the row's
		// text, enabled and checked state covers the controls inside it. Row collection must not
		// depend on the list being an AbstractWidget: older Minecraft lists are only GuiEventListeners.
		for (RowViewport viewport : rowViewports) {
			for (int index = 0; index < viewport.rowCount(); index++) result.add(GuiElement.ofRow(id++, viewport, index));
		}
		return new GuiElements(result);
	}

	private static void collectAttachedWidgets(GuiEventListener listener, LinkedHashSet<AbstractWidget> widgets, List<RowViewport> rowViewports) {
		if (listener instanceof AbstractWidget widget) widgets.add(widget);
		// Row viewport lists are emitted as one element per row carrying the row's text and
		// checkbox state; buttons inside rows (a row's help control) stay individually addressable.
		if (listener instanceof RowViewport viewport) {
			rowViewports.add(viewport);
			if (listener instanceof ContainerEventHandler container) {
				for (GuiEventListener child : container.children()) collectRowButtons(child, widgets);
			}
			return;
		}
		if (listener instanceof Screen || !(listener instanceof ContainerEventHandler container)) return;
		for (GuiEventListener child : container.children()) collectAttachedWidgets(child, widgets, rowViewports);
	}

	private static void collectRowButtons(GuiEventListener listener, LinkedHashSet<AbstractWidget> widgets) {
		if (listener instanceof ContainerEventHandler container) {
			for (GuiEventListener child : container.children()) collectRowButtons(child, widgets);
			return;
		}
		if (!(listener instanceof AbstractWidget widget)) return;
		// The row's checkbox is represented by the row element itself; exposing it again as a bare widget would duplicate every row.
		if (listener instanceof CheckboxWidget) return;
		widgets.add(widget);
	}

	private static JsonArray elementsJson(List<GuiElement> elements) {
		JsonArray a = new JsonArray();
		for (GuiElement e : elements) {
			JsonObject o = new JsonObject();
			o.addProperty("id", e.id());
			o.addProperty("text", e.text());
			List<String> keys = e.translationKeys();
			if (keys.size() == 1) o.addProperty("key", keys.get(0));
			else if (keys.size() > 1) {
				// Several lang keys may share a rendered value (e.g. two "Download" labels); matching treats them as a set.
				JsonArray arr = new JsonArray();
				keys.forEach(arr::add);
				o.add("keys", arr);
			}
			o.addProperty("x", e.x());
			o.addProperty("y", e.y());
			o.addProperty("width", e.width());
			o.addProperty("height", e.height());
			o.addProperty("enabled", e.enabled());
			o.addProperty("visible", e.visible());
			Boolean checked = e.checked();
			if (checked != null) o.addProperty("checked", checked);
			if (e.partial()) o.addProperty("partial", true);
			o.addProperty("type", e.type());
			o.addProperty("class", e.className());
			a.add(o);
		}
		return a;
	}

	private static List<String> translationKeys(Component component) {
		if (component == null) return List.of();
		/*? if >= 1.19.2 {*/
		if (component.getContents() instanceof TranslatableContents translatable) return List.of(translatable.getKey());
		/*?} else {*/
		/*if (component instanceof TranslatableComponent translatable) return List.of(translatable.getKey());
		*//*?}*/
		for (Component sibling : component.getSiblings()) {
			List<String> keys = translationKeys(sibling);
			if (!keys.isEmpty()) return keys;
		}
		// Our own keys render as plain L10n literals (never vanilla translatables, so a server pack cannot
		// puppeteer our UI), so the translation key is recovered from the rendered text for stable matching.
		return L10n.keysFor(component.getString());
	}

	private static <T> String onMain(ThrowingSupplier<T> supplier) throws Exception {
		Minecraft c = Minecraft.getInstance();
		CompletableFuture<T> f = new CompletableFuture<>();
		c.execute(() -> {
			try {
				f.complete(supplier.get());
			} catch (Exception e) {
				f.completeExceptionally(e);
			}
		});
		T result = f.get();
		return result instanceof String string ? string : String.valueOf(result);
	}

	private static void writeFile(Path p, String c) throws IOException {
		Path t = p.resolveSibling(p.getFileName() + ".tmp");
		Files.writeString(t, c, StandardCharsets.UTF_8);
		Files.move(t, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private static JsonObject base() {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		return o;
	}

	private static String ok() {
		return "{\"ok\":true}";
	}

	private static String err(String m) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.addProperty("error", m == null ? "unknown" : m);
		return o.toString();
	}

	private static String err(String code, String message) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.addProperty("code", code);
		o.addProperty("error", message);
		return o.toString();
	}

	private static long screenRevision(Screen screen) {
		// The revision must change whenever the widget set is replaced (rebuildWidgets swaps
		// screen.children()), not only when the screen instance changes: element ids are
		// positional over children, so a rebuild shifts every id and a click dumped before the
		// rebuild would otherwise land on a different button.
		if (screen == null) return 0;
		long revision = System.identityHashCode(screen);
		for (GuiEventListener child : screen.children()) {
			revision = revision * 31 + System.identityHashCode(child);
		}
		return revision;
	}

	private static boolean has(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && !e.isJsonNull();
	}

	private static String optString(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && !e.isJsonNull() ? e.getAsString() : "";
	}

	private static int optInt(JsonObject o, String k, int d) {
		JsonElement e = o.get(k);
		return e != null && !e.isJsonNull() ? e.getAsInt() : d;
	}

	private static long optLong(JsonObject o, String k, long d) {
		JsonElement e = o.get(k);
		return e != null && !e.isJsonNull() ? e.getAsLong() : d;
	}

	/*? if >= 1.19.2 {*/
	private static Component translatable(String key) {
		return Component.translatable(key);
	}
	/*?} else {*/
	/*private static TranslatableComponent translatable(String key) {
		return new TranslatableComponent(key);
	}
	*//*?}*/

	private record GuiElements(List<GuiElement> all) {
		GuiElement byId(int id) {
			for (GuiElement element : all) {
				if (element.id() == id) return element;
			}
			return null;
		}

		List<GuiElement> buttons() {
			return all.stream().filter(e -> e.widget() instanceof Button).toList();
		}

		List<GuiElement> textFields() {
			return all.stream().filter(e -> e.widget() instanceof EditBox).toList();
		}

		List<GuiElement> other() {
			return all.stream().filter(e -> !(e.widget() instanceof Button) && !(e.widget() instanceof EditBox)).toList();
		}
	}

	/** One clickable GUI thing: a regular widget, or one row of a scrollable list. */
	private record GuiElement(int id, AbstractWidget widget, RowViewport rows, int rowIndex, String rowText, boolean rowEnabled, Boolean rowChecked, boolean rowPartial) {
		static GuiElement of(int id, AbstractWidget widget) {
			return new GuiElement(id, widget, null, -1, "", false, null, false);
		}

		static GuiElement ofRow(int id, RowViewport rows, int rowIndex) {
			RowViewport.RowView view = rows.rowView(rowIndex);
			return new GuiElement(id, null, rows, rowIndex, view.text(), view.enabled(), view.checked(), view.partial());
		}

		String text() {
			if (rows != null) return rowText;
			return widget instanceof EditBox editBox ? editBox.getValue() : widget.getMessage().getString();
		}

		List<String> translationKeys() {
			return rows != null ? List.of() : AutoTestBridge.translationKeys(widget.getMessage());
		}

		int x() {
			if (rows != null) return rows.rowLeft();
			/*? if >= 1.19.4 {*/
			return widget.getX();
			/*?} else {*/
			/*return widget.x;
			*//*?}*/
		}

		int y() {
			if (rows != null) return rows.rowTop(rowIndex);
			/*? if >= 1.19.4 {*/
			return widget.getY();
			/*?} else {*/
			/*return widget.y;
			*//*?}*/
		}

		int width() {
			return rows != null ? rows.rowWidth() : widget.getWidth();
		}

		int height() {
			return rows != null ? rows.rowHeight() : widget.getHeight();
		}

		/** Center of the element's current bounds; list rows are scrolled into view first so scrolled-out rows still receive the click. */
		int[] clickPoint() {
			if (rows != null) {
				rows.revealRow(rowIndex);
				return new int[] { rows.rowLeft() + rows.rowWidth() / 2, rows.rowTop(rowIndex) + rows.rowHeight() / 2 };
			}
			return new int[] { x() + width() / 2, y() + height() / 2 };
		}

		boolean enabled() {
			return rows != null ? rowEnabled : widget.active;
		}

		boolean visible() {
			return rows != null || widget.visible;
		}

		/** Selected state of a real checkbox or checkbox-like row, or null when there is none. */
		Boolean checked() {
			if (rows != null) return rowChecked;
			return widget instanceof CheckboxWidget checkbox ? checkbox.selected() : null;
		}

		/** Indeterminate state of a tri-state checkbox row: some, but not all, of what it stands for is in. */
		boolean partial() {
			if (rows != null) return rowPartial;
			return widget instanceof CheckboxWidget checkbox && checkbox.partial();
		}

		String type() {
			if (rows != null) return "ListRow";
			if (widget instanceof Button) return "Button";
			if (widget instanceof EditBox) return "TextField";
			return widget.getClass().getSimpleName();
		}

		String className() {
			return rows != null ? rows.getClass().getName() : widget.getClass().getName();
		}
	}

	@FunctionalInterface
	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}
}
