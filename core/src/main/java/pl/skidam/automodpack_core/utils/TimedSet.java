package pl.skidam.automodpack_core.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
* A simple set-like structure where elements expire after a certain lifetime.
* */
public class TimedSet<T> {
    private record Entry(long expiryTime) { }

    private final Map<T, Entry> map = new ConcurrentHashMap<>();
    private final long lifetimeMillis;

    public TimedSet(long lifetimeMillis) {
        this.lifetimeMillis = lifetimeMillis;
    }

    public void add(T value) {
        cleanup();
        map.put(value, new Entry(System.currentTimeMillis() + lifetimeMillis));
    }

    public boolean contains(T value) {
        cleanup();
        Entry e = map.get(value);
        return e != null && e.expiryTime() > System.currentTimeMillis();
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> e.getValue().expiryTime() <= now);
    }

//    // Example usage
//    public static void main(String[] args) throws InterruptedException {
//        TimedSet<String> set = new TimedSet<>(3000); // 3 seconds
//        set.add("hello");
//        System.out.println(set.contains("hello")); // true
//        Thread.sleep(3500);
//        System.out.println(set.contains("hello")); // false
//    }
}
