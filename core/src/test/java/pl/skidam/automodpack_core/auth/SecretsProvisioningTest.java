package pl.skidam.automodpack_core.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ServerConfigJsons;

class SecretsProvisioningTest {
	private ServerConfigJsons.ServerConfigFieldsV3 previousConfig;

	@BeforeEach
	void setUp() {
		previousConfig = Constants.serverConfig;
		Constants.serverConfig = new ServerConfigJsons.ServerConfigFieldsV3();
		Constants.serverConfig.validateSecrets = true;
		ProvisioningSecretStore.reset();
	}

	@AfterEach
	void tearDown() {
		ProvisioningSecretStore.reset();
		Constants.serverConfig = previousConfig;
	}

	@Test
	void provisioningSecretBypassesPlayerLookup() {
		Secrets.Secret secret = Secrets.generateSecret();
		ProvisioningSecretStore.load(secret.secret());
		assertTrue(Secrets.isSecretValid(secret.secret(), InetSocketAddress.createUnresolved("127.0.0.1", 25565)));
	}
}
