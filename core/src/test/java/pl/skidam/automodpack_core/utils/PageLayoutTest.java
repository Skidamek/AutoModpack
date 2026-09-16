package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class PageLayoutTest {
	@Test
	void keepsCategoryHeaderAndGroupsOnTheSamePageWhenTheyFit() {
		List<PageLayout.Range> pages = PageLayout.paginate(17, 14, List.of(new PageLayout.Range(13, 17)));

		assertEquals(List.of(new PageLayout.Range(0, 13), new PageLayout.Range(13, 17)), pages);
	}

	@Test
	void splitsCategoryThatCanNeverFitOnOnePage() {
		List<PageLayout.Range> pages = PageLayout.paginate(18, 5, List.of(new PageLayout.Range(3, 18)));

		assertEquals(List.of(
				new PageLayout.Range(0, 5),
				new PageLayout.Range(5, 10),
				new PageLayout.Range(10, 15),
				new PageLayout.Range(15, 18)), pages);
	}
}
