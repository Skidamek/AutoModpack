package pl.skidam.automodpack_core.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.storage.StoragePaths;

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

	/** A disk-backed provisioning secret revokes in the running process when its credential file is deleted: the next validation miss answers null instead of the cached value. */
	@Test
	void deletingTheCredentialFileRevokesTheProvisioningSecret() throws Exception {
		Constants.serverConfig.validateSecrets = true;
		try {
			String secret = ProvisioningSecretStore.ensure();
			Files.deleteIfExists(StoragePaths.PROVISIONING_SECRET_FILE);
			assertFalse(Secrets.isSecretValid(secret, InetSocketAddress.createUnresolved("127.0.0.1", 25565)), "the deleted file revokes the capability");
		} finally {
			ProvisioningSecretStore.reset();
		}
	}

	/** An in-process seed no file backs (the bootstrap test seam) is answered from memory and is not subject to the disk re-check. */
	@Test
	void inProcessSeedSurvivesWithoutADiskFile() {
		Secrets.Secret secret = Secrets.generateSecret();
		ProvisioningSecretStore.load(secret.secret());
		assertEquals(secret.secret(), ProvisioningSecretStore.get());
	}
}
