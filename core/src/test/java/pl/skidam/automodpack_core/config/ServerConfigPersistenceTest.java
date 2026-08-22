package pl.skidam.automodpack_core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigPersistenceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void saveReloadPreservesUnknownTopLevelAndSharedSecurityFields() throws Exception {
        Path configFile = tempDirectory.resolve("automodpack-server.json");
        Path previous = pl.skidam.automodpack_core.GlobalVariables.serverConfigFile;
        pl.skidam.automodpack_core.GlobalVariables.serverConfigFile = configFile;
        try {
            Files.writeString(configFile, """
                    {
                      "DO_NOT_CHANGE_IT": 2,
                      "futureTopLevel": {"enabled": true},
                      "sharedSecurity": {
                        "enabled": true,
                        "nodeId": "BackendA",
                        "directory": "C:/path/to/automodpack-security",
                        "futureSharedField": "keep-me"
                      }
                    }
                    """, StandardCharsets.UTF_8);
            Jsons.ServerConfigFieldsV2 config = ConfigTools.load(configFile, Jsons.ServerConfigFieldsV2.class);
            ConfigTools.save(configFile, config);
            String saved = Files.readString(configFile, StandardCharsets.UTF_8);
            assertTrue(saved.contains("futureTopLevel"));
            assertTrue(saved.contains("futureSharedField"));
        } finally {
            pl.skidam.automodpack_core.GlobalVariables.serverConfigFile = previous;
        }
    }
}
