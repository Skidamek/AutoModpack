package pl.skidam.automodpack_core.utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

@SuppressWarnings("unchecked")
public class ObservableMap<K, V> {

    private final ConcurrentHashMap<K, V> synchronizedMap;
    private List<BiConsumer<K, V>> onPutCallbacks = new ArrayList<>();
    private List<BiConsumer<K, V>> onRemoveCallbacks = new ArrayList<>() ;

    public ObservableMap() {
        synchronizedMap = new ConcurrentHashMap<>();
    }

    public ObservableMap(int initialCapacity) {
        synchronizedMap = new ConcurrentHashMap<>(initialCapacity);
    }

    public ObservableMap(Map<? extends K, ? extends V> m) {
        synchronizedMap = new ConcurrentHashMap<>(m);
    }

    public synchronized V put(K key, V value) {
        V result = synchronizedMap.put(key, value);
        for (BiConsumer<K, V> callback : onPutCallbacks) {
            callback.accept(key, value);
        }
        return result;
    }

    public synchronized V remove(Object key) {
        V result = synchronizedMap.remove((K) key);
        for (BiConsumer<K, V> callback : onRemoveCallbacks) {
            callback.accept((K) key, result);
        }
        return result;
    }

    public void clear() {
        synchronizedMap.clear();
        this.onPutCallbacks = new ArrayList<>();
        this.onRemoveCallbacks = new ArrayList<>();
    }

    public void addOnPutCallback(BiConsumer<K, V> callback) {
        onPutCallbacks.add(callback);
    }

    public void addOnRemoveCallback(BiConsumer<K, V> callback) {
        onRemoveCallbacks.add(callback);
    }

    public Map<K, V> getMap() {
        return Collections.unmodifiableMap(synchronizedMap);
    }
}
