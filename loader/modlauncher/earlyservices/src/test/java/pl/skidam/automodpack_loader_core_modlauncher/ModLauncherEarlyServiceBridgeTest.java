package pl.skidam.automodpack_loader_core_modlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;

/**
 * The in-place services ModLauncher never discovered see the lifecycle it gives the ones it did, as Connector's
 * MixinTransmog and mixin-booster in one pack need: booster stands down when {@code connector_loader} is among the
 * services, and reads its {@code config} option values even when none were given.
 */
class ModLauncherEarlyServiceBridgeTest {

	private static final String FORWARDER = "automodpack_early_services";

	@AfterEach
	void forgetServices() {
		ModLauncherEarlyServiceBridge.clearTransformationServices();
	}

	@Test
	void inPlaceServicesSeeEachOthersNamesAndTheirOwnOptions() {
		Service connector = new Service("connector_loader", null);
		Service booster = new Service("mixin-booster", "connector_loader");
		ModLauncherEarlyServiceBridge.addTransformationService(Path.of("Connector.jar"), connector);
		ModLauncherEarlyServiceBridge.addTransformationService(Path.of("mixin-booster.jar"), booster);

		ModLauncherEarlyServiceBridge.onLoad(null, Set.of(FORWARDER, "fml"));
		assertTrue(booster.otherServices.containsAll(Set.of(FORWARDER, "fml", "connector_loader", "mixin-booster")), booster.otherServices::toString);
		assertTrue(booster.stoodDown, "booster defers to the in-place Connector it can now see");
		assertFalse(connector.stoodDown);

		OptionParser parser = new OptionParser();
		parser.allowsUnrecognizedOptions();
		ModLauncherEarlyServiceBridge.arguments((option, description) -> parser.accepts(FORWARDER + "." + option, description));
		OptionSet given = parser.parse("--" + FORWARDER + ".connector_loader.config", "connector.mixins.json");
		ModLauncherEarlyServiceBridge.argumentValues(new ITransformationService.OptionResult() {
			@Override
			public <V> V value(OptionSpec<V> option) {
				return given.valueOf(option);
			}

			@Override
			public <V> List<V> values(OptionSpec<V> option) {
				assertTrue(option.options().stream().allMatch(name -> name.startsWith(FORWARDER + ".")), "ModLauncher refuses options outside the forwarder's prefix");
				return given.valuesOf(option);
			}
		});
		assertEquals(List.of("connector.mixins.json"), connector.configs);
		assertEquals(List.of(), booster.configs, "no option given still reads as no configs, never null");
	}

	/**
	 * As ModLauncher's decorator and validation: an incompatible environment marks the service invalid after every onLoad
	 * ran, and the launch then fails naming it, so no later stage ever runs.
	 */
	@Test
	void anIncompatibleEnvironmentFromOnLoadFailsTheLaunchNamingTheService() {
		Service incompatible = new Service("incompatible", null, new IncompatibleEnvironmentException("not this loader"));
		Service healthy = new Service("healthy", null);
		ModLauncherEarlyServiceBridge.addTransformationService(Path.of("incompatible.jar"), incompatible);
		ModLauncherEarlyServiceBridge.addTransformationService(Path.of("healthy.jar"), healthy);

		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ModLauncherEarlyServiceBridge.onLoad(null, Set.of(FORWARDER)));
		assertEquals("Invalid Services found incompatible", failure.getMessage());
		assertEquals(List.of("onLoad"), incompatible.calls);
		assertEquals(List.of("onLoad"), healthy.calls);
	}

	/** ModLauncher catches nothing else around a stage, so any other failure reaches the launch unchanged. */
	@Test
	void anyOtherFailureFromAStageFailsTheLaunchUnchanged() {
		IllegalStateException broken = new IllegalStateException("broken bootstrap");
		ModLauncherEarlyServiceBridge.addTransformationService(Path.of("broken.jar"), new Service("broken", null, broken));

		assertSame(broken, assertThrows(IllegalStateException.class, () -> ModLauncherEarlyServiceBridge.onLoad(null, Set.of(FORWARDER))));
	}

	private static final class Service implements ITransformationService {
		private final String name;
		private final String defersTo;
		private ArgumentAcceptingOptionSpec<String> config;
		private Set<String> otherServices;
		private boolean stoodDown;
		private List<String> configs;
		private final Exception onLoadFailure;
		private final List<String> calls = new ArrayList<>();

		private Service(String name, String defersTo) {
			this(name, defersTo, null);
		}

		private Service(String name, String defersTo, Exception onLoadFailure) {
			this.name = name;
			this.defersTo = defersTo;
			this.onLoadFailure = onLoadFailure;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public void arguments(BiFunction<String, String, OptionSpecBuilder> argumentBuilder) {
			calls.add("arguments");
			config = argumentBuilder.apply("config", "a mixin config to load").withRequiredArg().ofType(String.class);
		}

		@Override
		public void argumentValues(OptionResult option) {
			configs = option.values(config);
		}

		@Override
		public void initialize(IEnvironment environment) {
			calls.add("initialize");
		}

		@Override
		public List<Resource> beginScanning(IEnvironment environment) {
			calls.add("beginScanning");
			return List.of();
		}

		@Override
		public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
			calls.add("onLoad");
			if (onLoadFailure instanceof IncompatibleEnvironmentException incompatible) throw incompatible;
			if (onLoadFailure != null) throw (RuntimeException) onLoadFailure;
			this.otherServices = otherServices;
			stoodDown = otherServices.contains(defersTo);
		}

		@Override
		public List<ITransformer> transformers() {
			calls.add("transformers");
			return List.of();
		}
	}
}
