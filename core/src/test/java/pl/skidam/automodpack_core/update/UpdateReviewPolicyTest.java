package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.modpack.generation.PackTarget;

class UpdateReviewPolicyTest {
	private static final PackTarget INSTALLED = target("packaa1", "2222222222222222222222222222222222222222");
	private static final PackTarget NEXT_GENERATION = target("packaa1", "3333333333333333333333333333333333333333");
	private static final PackTarget OTHER_PACK = target("packbb1", "5555555555555555555555555555555555555555");

	@Test
	void reviewsFirstInstallGenerationChangesAndPlanImpactButAllowsAnAuthorizedNoOp() {
		assertAll(
				() -> assertTrue(UpdateReviewPolicy.requiresPlayerReview(true, null, INSTALLED, false)),
				() -> assertTrue(UpdateReviewPolicy.requiresPlayerReview(false, INSTALLED, NEXT_GENERATION, false)),
				() -> assertTrue(UpdateReviewPolicy.requiresPlayerReview(false, INSTALLED, OTHER_PACK, false)),
				() -> assertTrue(UpdateReviewPolicy.requiresPlayerReview(false, INSTALLED, INSTALLED, true)),
				() -> assertFalse(UpdateReviewPolicy.requiresPlayerReview(false, INSTALLED, INSTALLED, false)));
	}

	private static PackTarget target(String modpackId, String contentToken) {
		return new PackTarget(modpackId, contentToken, "6666666666666666666666666666666666666666", "7777777777777777777777777777777777777777");
	}
}
