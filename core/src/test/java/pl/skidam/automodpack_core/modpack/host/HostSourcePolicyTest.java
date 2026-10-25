package pl.skidam.automodpack_core.modpack.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.loader.LoaderManagerService.EnvironmentType;
import pl.skidam.automodpack_core.modpack.PackAbsence;

class HostSourcePolicyTest {
	@Test
	void aServerAlwaysServesItsOwnGeneration() {
		assertEquals(new HostSource.Generated(), HostSourcePolicy.select(EnvironmentType.SERVER, null, false));
		assertEquals(new HostSource.Generated(), HostSourcePolicy.select(EnvironmentType.SERVER, "pack111", true));
		assertEquals(new HostSource.Generated(), HostSourcePolicy.select(EnvironmentType.UNIVERSAL, "pack111", false));
		assertEquals(new HostSource.Generated(), HostSourcePolicy.select(null, "pack111", false));
	}

	@Test
	void aClientWithoutAnInstalledPackGenerates() {
		assertEquals(new HostSource.Generated(), HostSourcePolicy.select(EnvironmentType.CLIENT, null, false));
		assertEquals(new HostSource.Generated(), HostSourcePolicy.select(EnvironmentType.CLIENT, " ", true));
	}

	@Test
	void aClientRelaysItsActivePackOnlyOnceShared() {
		assertEquals(new HostSource.Relay("pack111"), HostSourcePolicy.select(EnvironmentType.CLIENT, "pack111", true));
		HostSource unshared = HostSourcePolicy.select(EnvironmentType.CLIENT, "pack111", false);
		PackAbsence absence = assertInstanceOf(HostSource.None.class, unshared).absence();
		assertEquals(PackAbsence.Kind.NOT_SHARED, absence.kind());
		assertTrue(absence.detail().contains("pack111"));
	}

	@Test
	void onlyAGeneratingRoleMayRewriteAGeneration() {
		HostRole unavailable = HostRole.unavailable("disk full");
		assertTrue(unavailable.generationRefusal().orElseThrow().contains("disk full"));
		assertEquals(PackAbsence.Kind.HOST_UNAVAILABLE, unavailable.absence().kind());
		assertTrue(unavailable.integrated());
	}
}
