package pl.skidam.automodpack_core.security;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.Jsons;

import java.nio.file.Path;

/** Resolves server security paths after the server configuration is loaded. */
public final class ServerSecurityPathManager {
    private ServerSecurityPathManager() {
    }

    public static void configure(Jsons.ServerConfigFieldsV2 config) {
        if (config == null) {
            throw new IllegalArgumentException("Server config cannot be null");
        }

        SharedSecurityPaths resolved = SharedSecurityPaths.resolve(
                config.sharedSecurity,
                Path.of(System.getProperty("user.dir"))
        );

        if (resolved.enabled()) {
            GlobalVariables.sharedSecurityPaths = resolved;
            GlobalVariables.serverSecretsFile = resolved.secretsFile();
            GlobalVariables.serverCertFile = resolved.certificateFile();
            GlobalVariables.serverPrivateKeyFile = resolved.privateKeyFile();
        } else {
            GlobalVariables.sharedSecurityPaths = null;
            GlobalVariables.serverSecretsFile = GlobalVariables.privateDir.resolve("automodpack-secrets.json");
            GlobalVariables.serverCertFile = GlobalVariables.privateDir.resolve("cert.crt");
            GlobalVariables.serverPrivateKeyFile = GlobalVariables.privateDir.resolve("key.pem");
        }
    }

    public static boolean isEnabled() {
        return GlobalVariables.sharedSecurityPaths != null
                && GlobalVariables.sharedSecurityPaths.enabled();
    }
}
