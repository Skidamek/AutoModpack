package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.DownloadScheduler;

class RescuePolicyTest {

	private static final long MiB = 1024L * 1024L;
	private static final long SECOND = 1_000_000_000L;

	@Test
	void aProbeWaitsUntilEveryCandidateOfTheFileIsMeasured() {
		DownloadScheduler scheduler = new DownloadScheduler();
		RescuePolicy policy = new RescuePolicy(scheduler);
		List<String> platforms = List.of("cdn.example.com", "other.example.com");
		assertFalse(policy.worthProbing(platforms));
		scheduler.report("cdn.example.com", 100 * 1024, SECOND);
		assertFalse(policy.worthProbing(platforms), "the unmeasured sibling may still be the fast one");
		scheduler.report("other.example.com", 100 * 1024, SECOND);
		assertTrue(policy.worthProbing(platforms));
		scheduler.report(RescuePolicy.HOST_DOMAIN, 50 * MiB, SECOND);
		assertFalse(policy.worthProbing(platforms), "a measured host is decided by verdicts");
	}

	@Test
	void anAdequatePlatformDomainKeepsItsTrafficAtAnyRatio() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report(RescuePolicy.HOST_DOMAIN, 1050 * MiB, SECOND);
		scheduler.report("cdn.example.com", 100 * MiB, SECOND);
		RescuePolicy policy = new RescuePolicy(scheduler);
		List<String> platforms = List.of("cdn.example.com");
		assertEquals(platforms, policy.offer(platforms));
		assertFalse(policy.hostFarFaster("cdn.example.com"));

		DownloadScheduler atBar = new DownloadScheduler();
		atBar.report(RescuePolicy.HOST_DOMAIN, 105 * MiB, SECOND);
		atBar.report("cdn.example.com", 10 * MiB, SECOND);
		RescuePolicy atBarPolicy = new RescuePolicy(atBar);
		assertEquals(platforms, atBarPolicy.offer(platforms), "a domain exactly at the bar is adequate");
		assertFalse(atBarPolicy.hostFarFaster("cdn.example.com"));
	}

	@Test
	void anInadequateDomainIsRescuedOnlyAtTenfold() {
		DownloadScheduler rescued = new DownloadScheduler();
		rescued.report(RescuePolicy.HOST_DOMAIN, 55 * MiB, SECOND);
		rescued.report("cdn.example.com", 5 * MiB, SECOND);
		RescuePolicy engage = new RescuePolicy(rescued);
		assertEquals(List.of(RescuePolicy.HOST_DOMAIN, "cdn.example.com"), engage.offer(List.of("cdn.example.com")));
		assertTrue(engage.hostFarFaster("cdn.example.com"));

		DownloadScheduler stays = new DownloadScheduler();
		stays.report(RescuePolicy.HOST_DOMAIN, 40 * MiB, SECOND);
		stays.report("cdn.example.com", 5 * MiB, SECOND);
		RescuePolicy belowRatio = new RescuePolicy(stays);
		assertEquals(List.of("cdn.example.com"), belowRatio.offer(List.of("cdn.example.com")));
		assertFalse(belowRatio.hostFarFaster("cdn.example.com"));
	}

	@Test
	void aTaskEngagesOnlyWhenTheHostOutrunsEveryCandidate() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report(RescuePolicy.HOST_DOMAIN, 30 * MiB, SECOND);
		scheduler.report("fast-cdn.example.com", 8 * MiB, SECOND);
		scheduler.report("slow-cdn.example.com", 1 * MiB, SECOND);
		RescuePolicy policy = new RescuePolicy(scheduler);
		List<String> platforms = List.of("fast-cdn.example.com", "slow-cdn.example.com");
		assertEquals(platforms, policy.offer(platforms), "beating the slowest candidate is not outrunning the fastest");
		assertFalse(policy.hostFarFaster("fast-cdn.example.com"));
		assertTrue(policy.hostFarFaster("slow-cdn.example.com"), "the broken source still reroutes per domain");
	}

	@Test
	void oneAdequateCandidateKeepsThePlatformsFirstWhileTheBrokenOneStillReroutes() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report(RescuePolicy.HOST_DOMAIN, 1500 * MiB, SECOND);
		scheduler.report("healthy.example.com", 100 * MiB, SECOND);
		scheduler.report("broken.example.com", 1 * MiB, SECOND);
		RescuePolicy policy = new RescuePolicy(scheduler);
		List<String> platforms = List.of("healthy.example.com", "broken.example.com");
		assertEquals(platforms, policy.offer(platforms), "one adequate candidate keeps the platforms ahead at any ratio");
		assertFalse(policy.hostFarFaster("healthy.example.com"));
		assertTrue(policy.hostFarFaster("broken.example.com"), "the broken domain still reroutes per domain");
	}
}
