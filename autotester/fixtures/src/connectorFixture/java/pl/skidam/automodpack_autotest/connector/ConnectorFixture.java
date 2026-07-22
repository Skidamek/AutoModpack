package pl.skidam.automodpack_autotest.connector;

import net.fabricmc.api.ModInitializer;

public final class ConnectorFixture implements ModInitializer {

	@Override
	public void onInitialize() {
		System.out.println("AUTOMODPACK_CONNECTOR_FIXTURE_INITIALIZED");
	}
}
