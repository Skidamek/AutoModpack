package pl.skidam.automodpack_core.protocol;

import java.util.HashMap;
import java.util.Map;

public final class HostConnectionStats {
    private HostConnectionStats() {
    }

    public static <T> Map<T, Integer> countValues(Iterable<T> values) {
        Map<T, Integer> counts = new HashMap<>();
        for (T value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }
}
