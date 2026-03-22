package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static pl.skidam.automodpack_core.Constants.playerEndpointsFile;

public final class PlayerEndpointsStore {
    private static final Object LOCK = new Object();

    private static Jsons.PlayerEndpointsFields db;
    private static final Map<String, String> uuidByEndpointId = new HashMap<>();

    private PlayerEndpointsStore() {
    }

    public static void bindPlayer(String playerUuid, String playerName, byte[] endpointIdBytes) {
        bindPlayer(playerUuid, playerName, IrohIdentity.toHex(endpointIdBytes));
    }

    public static void bindPlayer(String playerUuid, String playerName, String endpointId) {
        if (playerUuid == null || playerUuid.isBlank() || endpointId == null || endpointId.isBlank()) {
            throw new IllegalArgumentException("Player UUID and endpoint ID must not be blank");
        }

        synchronized (LOCK) {
            ensureLoaded();

            long now = System.currentTimeMillis();
            Jsons.PlayerEndpointRecord existing = db.byUuid.get(playerUuid);
            long firstSeenAt = existing == null ? now : existing.firstSeenAt;

            if (existing != null && existing.endpointId != null && !existing.endpointId.isBlank()) {
                uuidByEndpointId.remove(existing.endpointId);
            }

            String displacedUuid = uuidByEndpointId.put(endpointId, playerUuid);
            if (displacedUuid != null && !displacedUuid.equals(playerUuid)) {
                db.byUuid.remove(displacedUuid);
            }

            db.byUuid.put(playerUuid, new Jsons.PlayerEndpointRecord(endpointId, playerName, firstSeenAt, now));
            save();
        }
    }

    public static String getEndpointIdForPlayer(String playerUuid) {
        synchronized (LOCK) {
            ensureLoaded();
            Jsons.PlayerEndpointRecord record = db.byUuid.get(playerUuid);
            return record == null ? null : record.endpointId;
        }
    }

    public static String getPlayerUuidForEndpoint(String endpointId) {
        synchronized (LOCK) {
            ensureLoaded();
            return uuidByEndpointId.get(endpointId);
        }
    }

    public static Map<String, Jsons.PlayerEndpointRecord> snapshotByUuid() {
        synchronized (LOCK) {
            ensureLoaded();
            return Collections.unmodifiableMap(new HashMap<>(db.byUuid));
        }
    }

    public static void clearForTests() {
        synchronized (LOCK) {
            db = null;
            uuidByEndpointId.clear();
        }
    }

    private static void ensureLoaded() {
        if (db != null) {
            return;
        }

        db = ConfigTools.load(playerEndpointsFile, Jsons.PlayerEndpointsFields.class);
        if (db == null) {
            db = new Jsons.PlayerEndpointsFields();
        }
        if (db.byUuid == null) {
            db.byUuid = new HashMap<>();
        }

        uuidByEndpointId.clear();
        for (Map.Entry<String, Jsons.PlayerEndpointRecord> entry : db.byUuid.entrySet()) {
            Jsons.PlayerEndpointRecord record = entry.getValue();
            if (record != null && record.endpointId != null && !record.endpointId.isBlank()) {
                uuidByEndpointId.put(record.endpointId, entry.getKey());
            }
        }
    }

    private static void save() {
        ConfigTools.save(playerEndpointsFile, db);
    }
}
