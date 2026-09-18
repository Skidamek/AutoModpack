package pl.skidam.automodpack_loader_core_forge;

/**
 * Instantiates a Forge generation's locator through the view interface both dispatchers reach it by
 * - the only reflection the dispatchers need, kept once instead of one copy each.
 */
final class LocatorViews {
	private LocatorViews() {}

	static <T> T instantiate(Class<T> view, String className) {
		try {
			return view.cast(Class.forName(className, true, LocatorViews.class.getClassLoader()).getDeclaredConstructor().newInstance());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Failed to instantiate Forge locator " + className, e);
		}
	}
}
