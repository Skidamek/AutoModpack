package pl.skidam.automodpack_core.launchers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.launchers.LauncherVersionSwapper.Axis;

class LauncherVersionSwapperTest {

	@TempDir
	Path dir;

	private static void write(Path path, String json) throws IOException {
		Files.writeString(path, json);
	}

	// --- no launcher metadata: fatal axes go manual, quiet changes stay quiet ---

	@Test
	void noAdapterGoesManualOnGameVersionChange() {
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch("fabric", "0.16.14", "1.21.1", true, "fabric", "1.20.1");
		assertFalse(plan.refused());
		assertTrue(plan.manual());
		assertTrue(plan.required());
		assertEquals(Set.of(Axis.GAME_VERSION), plan.axes());
	}

	@Test
	void noAdapterGoesManualOnLoaderTypeChange() {
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch("neoforge", "21.1.215", "1.20.1", true, "forge", "1.20.1");
		assertFalse(plan.refused());
		assertTrue(plan.manual());
		assertEquals(Set.of(Axis.LOADER_TYPE), plan.axes());
	}

	@Test
	void prismUnknownMinecraftVersionRefusesThePack() throws IOException {
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, mmcPack("1.20.1", "net.minecraftforge", "47.4.0"));
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch("fabric", "0.16.14", "1.21.1", true, "forge", "1.20.1", new MultiMCMeta(mmcPack),
				(uid, version) -> !"net.minecraft".equals(uid));
		assertTrue(plan.refused());
	}

	@Test
	void prismUnknownLoaderStillAppliesThePackWithoutWritingLoaderMetadata() throws IOException {
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, mmcPack("1.21.1", "net.minecraftforge", "47.4.0"));
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch("fabric", "0.16.14", "1.21.1", true, "forge", "1.21.1", new MultiMCMeta(mmcPack),
				(uid, version) -> "net.minecraft".equals(uid));
		assertFalse(plan.refused());
		assertFalse(plan.required());
	}

	@Test
	void prismUnknownLoaderKeepsAKnownMinecraftSwitch() throws IOException {
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, mmcPack("1.20.1", "net.minecraftforge", "47.4.0"));
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch("fabric", "0.16.14", "1.21.1", true, "forge", "1.20.1", new MultiMCMeta(mmcPack),
				(uid, version) -> "net.minecraft".equals(uid));
		assertFalse(plan.refused());
		assertEquals(Set.of(Axis.GAME_VERSION), plan.axes());
	}

	@Test
	void noAdapterIgnoresLoaderVersionBump() {
		// Launchers without metadata manage their own loader; a same-type version bump stays their business.
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch("fabric", "0.17.0", "1.20.1", true, "fabric", "1.20.1");
		assertFalse(plan.refused());
		assertFalse(plan.required());
		assertFalse(plan.manual());
	}

	// --- unadvertised versions: the pack is files-only, never the unsupported-loader refusal ---

	@Test
	void noAdvertisedVersionsIsFilesOnlyInsteadOfAnUnsupportedLoader() throws IOException {
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, mmcPack("1.20.1", "net.minecraftforge", "47.4.0"));
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch(null, null, null, true, "forge", "1.20.1", new MultiMCMeta(mmcPack),
				(uid, version) -> true);
		assertFalse(plan.refused());
		assertFalse(plan.required());
		assertFalse(plan.manual());
	}

	@Test
	void blankAdvertisedVersionsAreFilesOnlyToo() throws IOException {
		// The published manifest normalizes unadvertised versions to empty strings before a client sees them.
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, mmcPack("1.20.1", "net.minecraftforge", "47.4.0"));
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch("", "", "", true, "forge", "1.20.1", new MultiMCMeta(mmcPack),
				(uid, version) -> true);
		assertFalse(plan.refused());
		assertFalse(plan.required());
	}

	@Test
	void halfAdvertisedVersionsStillRefuse() throws IOException {
		// A pack without a loader that still names a Minecraft version is malformed, not files-only.
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, mmcPack("1.20.1", "net.minecraftforge", "47.4.0"));
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch("", "", "1.21.1", true, "forge", "1.20.1", new MultiMCMeta(mmcPack),
				(uid, version) -> true);
		assertTrue(plan.refused());
	}

	// --- MultiMC / Prism ---

	private String mmcPack(String mcVersion, String loaderUid, String loaderVersion) {
		return "{\"formatVersion\":1,\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"" + mcVersion + "\"},{\"uid\":\"" + loaderUid
				+ "\",\"version\":\"" + loaderVersion + "\",\"cachedVersion\":\"" + loaderVersion + "\"}]}";
	}

	@Test
	void multiMcDetectsAllThreeAxes() throws IOException {
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, mmcPack("1.20.1", "net.minecraftforge", "47.4.0"));
		MultiMCMeta meta = new MultiMCMeta(mmcPack);
		assertEquals(EnumSet.of(Axis.GAME_VERSION, Axis.LOADER_TYPE), meta.requiredAxes("fabric", "0.16.14", "1.21.1"));
		assertEquals(EnumSet.of(Axis.LOADER_VERSION), meta.requiredAxes("forge", "47.4.1", "1.20.1"));
		assertEquals(EnumSet.noneOf(Axis.class), meta.requiredAxes("forge", "47.4.0", "1.20.1"));
	}

	@Test
	void multiMcConvergesOnApply() throws IOException {
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, mmcPack("1.20.1", "net.minecraftforge", "47.4.0"));
		MultiMCMeta meta = new MultiMCMeta(mmcPack);
		meta.apply(EnumSet.allOf(Axis.class), "fabric", "0.16.14", "1.21.1");
		assertEquals(EnumSet.noneOf(Axis.class), meta.requiredAxes("fabric", "0.16.14", "1.21.1"));
	}

	@Test
	void multiMcUnreadableMetadataThrowsInsteadOfLookingConverged() throws IOException {
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, "{");
		assertThrows(IOException.class, () -> new MultiMCMeta(mmcPack).requiredAxes("fabric", "0.16.14", "1.21.1"));
	}

	@Test
	void multiMcReplaceLoaderDoesNotDuplicateExistingUid() throws IOException {
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack,
				"{\"formatVersion\":1,\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"1.20.1\"},{\"uid\":\"net.fabricmc.fabric-loader\",\"version\":\"0.16.14\"},{\"uid\":\"net.minecraftforge\",\"version\":\"47.4.0\"}]}");
		new MultiMCMeta(mmcPack).apply(EnumSet.of(Axis.LOADER_TYPE), "fabric", "0.17.0", "1.20.1");
		JsonObject persisted = JsonParser.parseString(Files.readString(mmcPack)).getAsJsonObject();
		long fabricCount = persisted.getAsJsonArray("components").asList().stream().map(element -> element.getAsJsonObject())
				.filter(component -> "net.fabricmc.fabric-loader".equals(component.get("uid").getAsString())).count();
		assertEquals(1, fabricCount);
		assertEquals(EnumSet.noneOf(Axis.class), new MultiMCMeta(mmcPack).requiredAxes("fabric", "0.17.0", "1.20.1"));
	}

	@Test
	void multiMcApplyRewritesCachedVersion() throws IOException {
		Path mmcPack = dir.resolve("mmc-pack.json");
		write(mmcPack, mmcPack("1.20.1", "net.fabricmc.fabric-loader", "0.16.14"));
		new MultiMCMeta(mmcPack).apply(EnumSet.of(Axis.LOADER_VERSION), "fabric", "0.17.0", "1.20.1");
		JsonObject persisted = JsonParser.parseString(Files.readString(mmcPack)).getAsJsonObject();
		JsonObject loader = persisted.getAsJsonArray("components").asList().stream().map(element -> element.getAsJsonObject())
				.filter(component -> "net.fabricmc.fabric-loader".equals(component.get("uid").getAsString())).findFirst().orElseThrow();
		assertEquals("0.17.0", loader.get("cachedVersion").getAsString());
	}

	// --- Pandora ---

	@Test
	void pandoraDetectsAxesAndNormalizesForge() throws IOException {
		Path info = dir.resolve("info_v1.json");
		write(info, "{\"minecraft_version\":\"1.20.1\",\"loader\":\"Fabric\",\"preferred_loader_version\":\"0.16.14\"}");
		PandoraMeta meta = new PandoraMeta(info);
		assertEquals(EnumSet.of(Axis.LOADER_TYPE), meta.requiredAxes("forge", "47.4.0", "1.20.1"));
		assertEquals(EnumSet.of(Axis.GAME_VERSION, Axis.LOADER_VERSION), meta.requiredAxes("fabric", "0.17.0", "1.21.1"));
		assertEquals(EnumSet.noneOf(Axis.class), meta.requiredAxes("fabric", "0.16.14", "1.20.1"));
	}

	@Test
	void pandoraConvergesOnApplyWithForgePrefix() throws IOException {
		Path info = dir.resolve("info_v1.json");
		write(info, "{\"minecraft_version\":\"1.20.1\",\"loader\":\"Fabric\",\"preferred_loader_version\":\"0.16.14\"}");
		PandoraMeta meta = new PandoraMeta(info);
		meta.apply(EnumSet.allOf(Axis.class), "forge", "47.4.0", "1.20.1");
		JsonObject persisted = JsonParser.parseString(Files.readString(info)).getAsJsonObject();
		// lowercase is the canonical serde form (loader.rs rename_all); the capitalized names are read aliases only
		assertEquals("forge", persisted.get("loader").getAsString());
		assertEquals("1.20.1-47.4.0", persisted.get("preferred_loader_version").getAsString());
		assertEquals(EnumSet.noneOf(Axis.class), meta.requiredAxes("forge", "47.4.0", "1.20.1"));
	}
}
