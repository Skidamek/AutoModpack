package pl.skidam.automodpack.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
/*? if >= 1.20.5 {*/
import net.minecraft.client.multiplayer.TransferState;
/*?}*/
import pl.skidam.automodpack.client.ui.FingerprintVerificationScreen;
import pl.skidam.automodpack_core.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public final class AutoTestBridge {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private AutoTestBridge() {}

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
    }

    private static void run(Path gameDir, String token) {
        Path dir = gameDir.resolve("automodpack/autotest");
        try { Files.createDirectories(dir); } catch (IOException e) {
            LOGGER.error("Cannot create autotest dir", e);
            return;
        }
        try { writeFile(dir.resolve("bridge-state.json"), "{\"status\":\"ready\"}"); } catch (IOException e) {
            LOGGER.error("Cannot write bridge state", e);
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
                    String response;
                    try {
                        JsonObject req = JsonParser.parseString(json).getAsJsonObject();
                        if (!token.equals(optString(req, "token"))) {
                            response = err("Authentication failed: invalid bridge token");
                        } else {
                            response = exec(req);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Bridge exec error", e);
                        response = err(e.getMessage());
                    }
                    writeFile(rsp, response);
                }
                Thread.sleep(100);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
              catch (Exception e) { LOGGER.error("Bridge error", e); }
        }
    }

    private static String exec(JsonObject req) throws Exception {
        return switch (optString(req, "op")) {
            case "ping" -> ok();
            case "get_screen" -> execOnMain(() -> scr(false));
            case "get_widgets" -> execOnMain(() -> scr(true));
            case "connect" -> connect(req);
            case "wait_fingerprint" -> execOnMain(() -> ok());
            case "set_text" -> execOnMain(() -> {
                Object w = widget(req);
                if (w instanceof EditBox e) {
                    e.setValue(optString(req, "text"));
                    Screen s = Minecraft.getInstance().screen;
                    if (s instanceof FingerprintVerificationScreen fps) {
                        fps.setInputText(optString(req, "text"));
                    }
                }
                return ok();
            });
            case "click" -> execOnMain(() -> {
                Object w = widget(req);
                if (w instanceof Button b) {
                    /*? if >= 1.21.10 {*/
                    var input = new net.minecraft.client.input.InputWithModifiers() {
                        public int input() { return 0; }
                        public int modifiers() { return 0; }
                    };
                    b.onPress(input);
                    /*?} else {*/
                    /*b.onPress();
                    *//*?}*/
                }
                return ok();
            });
            case "set_screen" -> execOnMain(() -> { Minecraft.getInstance().setScreen(new TitleScreen()); return ok(); });
            case "verify_fingerprint" -> execOnMain(() -> {
                Screen s = Minecraft.getInstance().screen;
                if (s instanceof FingerprintVerificationScreen fps) {
                    String fp = optString(req, "fingerprint");
                    List<Object> widgets = collectWidgets();
                    for (Object w : widgets) {
                        if (w instanceof EditBox e) {
                            e.setValue(fp);
                            break;
                        }
                    }
                    fps.setInputText(fp);
                    fps.verifyFingerprint();
                    return ok();
                }
                return err("not on FingerprintVerificationScreen");
            });
            case "quit" -> {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
                yield ok();
            }
            default -> err("Unknown bridge operation: '" + optString(req, "op") + "'");
        };
    }

    private static String scr(boolean detailed) {
        Screen s = Minecraft.getInstance().screen;
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("screenClass", s == null ? null : s.getClass().getName());
        o.addProperty("title", s == null ? null : s.getTitle().getString());
        if (detailed && s != null) {
            JsonArray a = new JsonArray();
            int i = 0;
            for (Object w : collectWidgets()) {
                if (!(w instanceof AbstractWidget aw)) continue;
                JsonObject wo = new JsonObject();
                wo.addProperty("id", i++);
                wo.addProperty("type", aw instanceof Button ? "Button" : aw instanceof EditBox ? "EditBox" : aw.getClass().getSimpleName());
                wo.addProperty("class", aw.getClass().getName());
                wo.addProperty("text", aw.getMessage().getString());
                /*? if >= 1.19.4 {*/
                wo.addProperty("x", aw.getX()); wo.addProperty("y", aw.getY());
                /*?} else {*/
                /*wo.addProperty("x", aw.x); wo.addProperty("y", aw.y);
                *//*?}*/
                wo.addProperty("active", aw.active); wo.addProperty("visible", aw.visible);
                a.add(wo);
            }
            o.add("widgets", a);
        }
        return o.toString();
    }

    private static String connect(JsonObject req) throws Exception {
        String addr = optString(req, "host") + ":" + optInt(req, "port", 25565);
        Minecraft c;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while ((c = Minecraft.getInstance()) == null) {
            if (System.nanoTime() > deadline) return err("Minecraft not initialized");
            Thread.sleep(100);
        }
        final Minecraft captured = c;
        if (captured.getOverlay() != null) {
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (captured.getOverlay() != null && System.nanoTime() < deadline) Thread.sleep(100);
        }
        CompletableFuture<String> f = new CompletableFuture<>();
        captured.execute(() -> {
            try {
                /*? if >= 1.20.5 {*/
                ConnectScreen.startConnecting(new TitleScreen(), captured, ServerAddress.parseString(addr), new ServerData("AutoTest", addr, ServerData.Type.OTHER), false, (TransferState) null);
                /*?} else if >= 1.20.4 {*/
                /*ConnectScreen.startConnecting(new TitleScreen(), captured, ServerAddress.parseString(addr), new ServerData("AutoTest", addr, ServerData.Type.OTHER), false);
                *//*?} else if >= 1.20.1 {*/
                /*ConnectScreen.startConnecting(new TitleScreen(), captured, ServerAddress.parseString(addr), new ServerData("AutoTest", addr, false), false);
                                *//*?} else {*/
                /*ConnectScreen.startConnecting(new TitleScreen(), captured, ServerAddress.parseString(addr), new ServerData("AutoTest", addr, false));
                *//*?}*/
                f.complete(ok());
            } catch (Exception e) { f.complete(err(e.getMessage())); }
        });
        return f.get(30, TimeUnit.SECONDS);
    }

    private static List<Object> collectWidgets() {
        Screen s = Minecraft.getInstance().screen;
        if (s == null) return List.of();
        return List.copyOf(s.children().stream().filter(w -> w instanceof AbstractWidget).toList());
    }

    private static Object widget(JsonObject req) {
        List<Object> all = collectWidgets();
        if (all.isEmpty()) throw new NullPointerException("no widgets");
        int wid = optInt(req, "widgetId", -1);
        JsonObject sel = req.getAsJsonObject("selector");
        if (wid < 0 && sel != null) wid = optInt(sel, "widgetId", -1);
        if (wid >= 0 && wid < all.size()) return all.get(wid);
        String selType = sel != null ? optString(sel, "type") : null;
        String selText = sel != null ? optString(sel, "text") : optString(req, "text");
        int idx = sel != null ? optInt(sel, "index", -1) : -1;
        var cand = selType != null && !selType.isEmpty() ? all.stream().filter(w -> (w instanceof Button ? "Button" : w instanceof EditBox ? "EditBox" : "").equalsIgnoreCase(selType)).toList() : all;
        if (selText != null && !selText.isEmpty()) {
            for (Object w : cand) { if (AbstractWidget.class.cast(w).getMessage().getString().equalsIgnoreCase(selText)) return w; }
            for (Object w : cand) { if (AbstractWidget.class.cast(w).getMessage().getString().toLowerCase().contains(selText.toLowerCase())) return w; }
        }
        if (idx >= 0 && idx < cand.size()) return cand.get(idx);
        if (!cand.isEmpty()) return cand.get(0);
        throw new IllegalArgumentException("widget not found");
    }

    private static String execOnMain(ThrowingSupplier<String> t) throws Exception {
        Minecraft c;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while ((c = Minecraft.getInstance()) == null) {
            if (System.nanoTime() > deadline) return err("Minecraft not initialized");
            Thread.sleep(100);
        }
        CompletableFuture<String> f = new CompletableFuture<>();
        c.execute(() -> { try { f.complete(t.get()); } catch (Exception e) { f.completeExceptionally(e); } });
        return f.get(60, TimeUnit.SECONDS);
    }

    private static void writeFile(Path p, String c) throws IOException {
        Path t = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(t, c, StandardCharsets.UTF_8);
        Files.move(t, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String ok() { return "{\"ok\":true}"; }
    private static String err(String m) { return "{\"ok\":false,\"error\":\"" + (m != null ? m.replace("\\", "\\\\").replace("\"", "\\\"") : "unknown") + "\"}"; }
    private static String optString(JsonObject o, String k) { JsonElement e = o.get(k); return e != null && !e.isJsonNull() ? e.getAsString() : ""; }
    private static int optInt(JsonObject o, String k, int d) { JsonElement e = o.get(k); return e != null && !e.isJsonNull() ? e.getAsInt() : d; }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }
}
