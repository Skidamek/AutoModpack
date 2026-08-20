package pl.skidam.automodpack_core.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.security.ServerSecurityPathManager;
import pl.skidam.automodpack_core.loader.NullGameCall;
import pl.skidam.automodpack_core.security.SharedSecurityPaths;

import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SecretsValidationTest {
    @TempDir
    Path tempDirectory;

    private Jsons.ServerConfigFieldsV2 previousConfig;
    private pl.skidam.automodpack_core.loader.GameCallService previousGameCall;

    @AfterEach
    void restoreGlobals() {
        GlobalVariables.serverConfig = previousConfig;
        GlobalVariables.GAME_CALL = previousGameCall == null ? new NullGameCall() : previousGameCall;
        GlobalVariables.sharedSecurityPaths = null;
        GlobalVariables.serverSecretsFile = GlobalVariables.privateDir.resolve("automodpack-secrets.json");
    }

    @Test
    void issuerAtLoginDoesNotRecheckHostWhitelist() throws Exception {
        setup("ISSUER_AT_LOGIN");
        String playerUuid = UUID.randomUUID().toString();
        Secrets.Secret secret = Secrets.generateSecret();
        new SharedSecretsStore(GlobalVariables.sharedSecurityPaths).save(playerUuid, secret, 336);
        AtomicInteger calls = new AtomicInteger();
        GlobalVariables.GAME_CALL = countingGameCall(calls, false);

        assertTrue(Secrets.isSecretValid(secret.secret(), null));
        assertEquals(0, calls.get());
    }

    @Test
    void hostRecheckUsesCurrentWhitelist() throws Exception {
        setup("HOST_RECHECK");
        String playerUuid = UUID.randomUUID().toString();
        Secrets.Secret secret = Secrets.generateSecret();
        new SharedSecretsStore(GlobalVariables.sharedSecurityPaths).save(playerUuid, secret, 336);
        AtomicInteger calls = new AtomicInteger();
        GlobalVariables.GAME_CALL = countingGameCall(calls, false);

        assertFalse(Secrets.isSecretValid(secret.secret(), null));
        assertEquals(1, calls.get());
    }

    private void setup(String authorizationMode) {
        previousConfig = GlobalVariables.serverConfig;
        previousGameCall = GlobalVariables.GAME_CALL;
        Jsons.ServerConfigFieldsV2 config = new Jsons.ServerConfigFieldsV2();
        config.validateSecrets = true;
        config.secretLifetime = 336;
        config.sharedSecurity = new Jsons.SharedSecurityFields();
        config.sharedSecurity.enabled = true;
        config.sharedSecurity.nodeId = "HostNode";
        config.sharedSecurity.directory = tempDirectory.toString();
        config.sharedSecurity.authorizationMode = authorizationMode;
        config.sharedSecurity.fsync = false;
        GlobalVariables.serverConfig = config;
        ServerSecurityPathManager.configure(config);
    }

    private static pl.skidam.automodpack_core.loader.GameCallService countingGameCall(AtomicInteger calls, boolean result) {
        return (SocketAddress address, String id) -> {
            calls.incrementAndGet();
            return result;
        };
    }
}
