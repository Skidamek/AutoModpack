package pl.skidam.automodpack_core.security;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SharedSecretsStore;
import pl.skidam.automodpack_core.config.Jsons;

import java.nio.file.Path;
import java.util.UUID;

/** Forked JVM worker used by SharedSecretsStoreTest. */
public final class SharedSecretsStoreProcessWorker {
    private SharedSecretsStoreProcessWorker() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        String nodeId = args[1];
        int count = Integer.parseInt(args[2]);
        Jsons.SharedSecurityFields config = new Jsons.SharedSecurityFields();
        config.enabled = true;
        config.nodeId = nodeId;
        config.directory = directory.toString();
        config.fsync = false;
        config.lockTimeoutMs = 10000;
        SharedSecretsStore store = new SharedSecretsStore(SharedSecurityPaths.resolve(config, directory));
        for (int i = 0; i < count; i++) {
            store.save(UUID.randomUUID().toString(), Secrets.generateSecret(), 336);
        }
    }
}
