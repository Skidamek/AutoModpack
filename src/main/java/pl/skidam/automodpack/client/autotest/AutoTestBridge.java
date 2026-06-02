package pl.skidam.automodpack.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
/*? if >=1.21.6 {*/
import net.minecraft.client.gui.screens.GenericMessageScreen;
/*?}*/
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
/*? if >= 1.21.10 {*/
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
/*?}*/
/*? if >= 1.20.5 {*/
import net.minecraft.client.multiplayer.TransferState;
/*?}*/
/*? if >= 1.19.2 {*/
import net.minecraft.network.chat.Component;
/*?} else {*/
/*import net.minecraft.network.chat.TranslatableComponent;
*//*?}*/

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public final class AutoTestBridge {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile Path bridgeDir;
    private static final AtomicBoolean CLIENT_READY = new AtomicBoolean(false);
    private static volatile boolean reloadFinished = false;

    public static void markReloadFinished() {
        reloadFinished = true;
    }

    public static boolean hasReloadFinished() {
        return reloadFinished;
    }

    public static void startIfEnabled() {
        if (!Boolean.getBoolean("automodpack.autotest")) return;
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

        Thread waiter = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100);
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof TitleScreen && hasReloadFinished()) {
                        LOGGER.info("AutoModpack: Client is ready, TitleScreen detected");
                        onClientReady();
                        return;
                    } else {
                        LOGGER.info("AutoModpack: Waiting for TitleScreen, current screen: {}", mc.screen == null ? "null" : mc.screen.getClass().getName());
                    }
                } catch (Exception ignored) {
                }
            }
        }, "AutoModpackReadyWaiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    public static void onClientReady() {
        if (!CLIENT_READY.compareAndSet(false, true)) return;
        Path dir = bridgeDir;
        if (dir == null) return;
        try {
            writeFile(dir.resolve("bridge-state.json"), "{\"status\":\"ready\"}");
        } catch (IOException e) {
            LOGGER.error("Cannot write client-ready state", e);
        }
    }

    private static void run(Path gameDir, String token) {
        Path dir = gameDir.resolve("automodpack/autotest");
        bridgeDir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("Cannot initialize autotest bridge directory", e);
            return;
        }

        LOGGER.info("AutoModpack bridge ready at {}", dir);
        Path cmd = dir.resolve("bridge-command.json");
        Path rsp = dir.resolve("bridge-response.json");
        while (true) {
            try {
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
            case "menu" -> onMain(AutoTestBridge::menu);
            case "close" -> onMain(AutoTestBridge::close);
            case "connect" -> onMain(() -> connect(req));
            case "disconnect" -> onMain(AutoTestBridge::disconnect);
            case "quit" -> onMain(AutoTestBridge::quit);
            case "render" -> render(req);
            default -> err("unknown operation: " + optString(req, "op"));
        };
    }

    private static JsonObject gui() {
        Minecraft c = Minecraft.getInstance();
        Screen s = c.screen;
        JsonObject o = base();
        o.addProperty("screenClass", s == null ? null : s.getClass().getName());
        o.addProperty("title", s == null ? null : s.getTitle().getString());
        o.add("buttons", elementsJson(elements(s).buttons()));
        o.add("textFields", elementsJson(elements(s).textFields()));
        o.add("other", elementsJson(elements(s).other()));
        o.add("elements", elementsJson(elements(s).all()));
        return o;
    }

    private static String click(JsonObject req) {
        Minecraft c = Minecraft.getInstance();
        Screen s = c.screen;
        if (s == null) return err("no screen");

        int button = optInt(req, "button", 0);
        int x;
        int y;
        if (has(req, "id")) {
            GuiElement e = elements(s).byId(optInt(req, "id", -1));
            if (e == null) return err("no gui element with id " + optInt(req, "id", -1));
            if (has(req, "enable") && req.get("enable").getAsBoolean() && e.widget() instanceof Button) {
                e.widget().active = true;
            }
            x = e.x() + e.width() / 2;
            y = e.y() + e.height() / 2;
        } else {
            x = optInt(req, "x", -1);
            y = optInt(req, "y", -1);
            if (x < 0 || y < 0) return err("click needs either id or x/y");
        }

        s.mouseMoved(x, y);
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
        Screen s = Minecraft.getInstance().screen;
        if (s == null) return err("no screen");

        int id = optInt(req, "id", -1);
        GuiElement e = elements(s).byId(id);
        if (e == null || !(e.widget() instanceof EditBox editBox)) {
            return err("no text field with id " + id);
        }

        editBox.setValue(optString(req, "text"));
        return ok();
    }

    private static String menu() {
        Minecraft c = Minecraft.getInstance();
        if (c.player == null) return err("not in game");
        if (c.screen != null) c.screen.onClose();
        c.pauseGame(false);
        return ok();
    }

    private static String close() {
        Minecraft c = Minecraft.getInstance();
        if (c.player == null) return err("not in game");
        if (c.screen != null) c.screen.onClose();
        return ok();
    }

    private static String connect(JsonObject req) {
        Minecraft c = Minecraft.getInstance();
        String host = optString(req, "host");
        int port = optInt(req, "port", 25565);
        if (host.isBlank()) return err("host is required");

        ServerAddress address = ServerAddress.parseString(host + ":" + port);
        ServerData serverData = new ServerData("AutoTest", address.toString()
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
        Minecraft c = Minecraft.getInstance();
        if (c.level == null) {
            c.setScreen(new TitleScreen());
            return ok();
        }

        /*? if >=1.21.6 {*/
        c.level.disconnect(translatable("multiplayer.status.quitting"));
        c.clearClientLevel(new GenericMessageScreen(translatable("multiplayer.disconnect.generic")));
        /*?} else {*/
        /*c.level.disconnect();
        *//*?}*/
        c.setScreen(new TitleScreen());
        return ok();
    }

    private static String quit() {
        Minecraft.getInstance().stop();
        return ok();
    }

    private static String render(JsonObject req) throws InterruptedException {
        int millis = Math.max(1, optInt(req, "time", 1000));
        boolean includeDuplicates = has(req, "includeDuplicates") && req.get("includeDuplicates").getAsBoolean();
        RenderedTextCollector.Session session = RenderedTextCollector.start();
        try {
            Thread.sleep(millis);
            JsonObject o = base();
            JsonArray a = new JsonArray();
            for (RenderedTextCollector.Entry entry : session.entries(includeDuplicates)) {
                JsonObject e = new JsonObject();
                e.addProperty("text", entry.text());
                e.addProperty("x", entry.x());
                e.addProperty("y", entry.y());
                a.add(e);
            }
            o.add("strings", a);
            return o.toString();
        } finally {
            session.close();
        }
    }

    private static GuiElements elements(Screen screen) {
        if (screen == null) return new GuiElements(List.of());

        LinkedHashSet<AbstractWidget> widgets = new LinkedHashSet<>();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget) widgets.add(widget);
        }
        findWidgets(screen, widgets, newSeenSet());

        List<GuiElement> result = new ArrayList<>();
        int id = 0;
        for (AbstractWidget widget : widgets) {
            result.add(new GuiElement(id++, widget));
        }
        return new GuiElements(result);
    }

    private static void findWidgets(Object object, Set<AbstractWidget> widgets, Set<Object> seen) {
        if (object == null || seen.contains(object)) return;
        seen.add(object);

        Class<?> type = object.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    collectWidgetValue(field.get(object), widgets, seen);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Best effort only. Screen#children is the primary source.
                }
            }
            type = type.getSuperclass();
        }
    }

    private static void collectWidgetValue(Object value, Set<AbstractWidget> widgets, Set<Object> seen) {
        if (value == null) return;
        if (value instanceof AbstractWidget widget) {
            widgets.add(widget);
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) collectWidgetValue(item, widgets, seen);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) collectWidgetValue(item, widgets, seen);
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) collectWidgetValue(Array.get(value, i), widgets, seen);
        }
    }

    private static JsonArray elementsJson(List<GuiElement> elements) {
        JsonArray a = new JsonArray();
        for (GuiElement e : elements) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("text", e.text());
            o.addProperty("x", e.x());
            o.addProperty("y", e.y());
            o.addProperty("width", e.width());
            o.addProperty("height", e.height());
            o.addProperty("enabled", e.widget().active);
            o.addProperty("visible", e.widget().visible);
            o.addProperty("type", e.type());
            o.addProperty("class", e.widget().getClass().getName());
            a.add(o);
        }
        return a;
    }

    private static Set<Object> newSeenSet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
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

    private record GuiElement(int id, AbstractWidget widget) {
        String text() {
            return widget instanceof EditBox editBox ? editBox.getValue() : widget.getMessage().getString();
        }

        int x() {
            /*? if >= 1.19.4 {*/
            return widget.getX();
            /*?} else {*/
            /*return widget.x;
            *//*?}*/
        }

        int y() {
            /*? if >= 1.19.4 {*/
            return widget.getY();
            /*?} else {*/
            /*return widget.y;
            *//*?}*/
        }

        int width() {
            return widget.getWidth();
        }

        int height() {
            return widget.getHeight();
        }

        String type() {
            if (widget instanceof Button) return "Button";
            if (widget instanceof EditBox) return "TextField";
            return widget.getClass().getSimpleName();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
