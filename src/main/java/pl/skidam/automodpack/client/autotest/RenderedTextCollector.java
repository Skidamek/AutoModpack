package pl.skidam.automodpack.client.autotest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RenderedTextCollector {
    private static final CopyOnWriteArrayList<Session> SESSIONS = new CopyOnWriteArrayList<>();

    private RenderedTextCollector() {}

    public static Session start() {
        Session session = new Session();
        SESSIONS.add(session);
        return session;
    }

    public static void record(String text, float x, float y) {
        if (text == null || text.isBlank() || SESSIONS.isEmpty()) return;
        Entry entry = new Entry(text, x, y);
        for (Session session : SESSIONS) {
            session.record(entry);
        }
    }

    public record Entry(String text, float x, float y) {}

    public static final class Session implements AutoCloseable {
        private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

        private void record(Entry entry) {
            entries.add(entry);
        }

        public List<Entry> entries(boolean includeDuplicates) {
            if (includeDuplicates) return new ArrayList<>(entries);

            Map<String, Entry> unique = new LinkedHashMap<>();
            for (Entry entry : entries) {
                unique.putIfAbsent(entry.text() + "\u0000" + entry.x() + "\u0000" + entry.y(), entry);
            }
            return new ArrayList<>(unique.values());
        }

        @Override
        public void close() {
            SESSIONS.remove(this);
        }
    }
}
