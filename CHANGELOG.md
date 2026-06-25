# Changelog

## [4.0.5 → 4.1.1] - MC 26.2 port

Ported AutoModpack to MC 26.2 (Fabric + NeoForge only — Forge is not maintained past 1.20.1 in this repo, so 26.2 follows the same Fabric/NeoForge-only pattern as 26.1).

**What was changed:**
- Added `26.2` Stonecutter version target: `stonecutter.properties.toml` (`deps.fabric-api = "0.153.0+26.2"`, `deps.neoforge = "26.2.0.7-beta"`), `match("26.2", "fabric", "neoforge")` in `settings.gradle.kts`.
- `buildSrc/src/main/kotlin/ModuleUtils.kt`: added `"26.2"` to the `neoforge-fml10` loader-module group (same FML ABI generation as 26.1/1.21.10/1.21.11) — required for the NeoForge build to resolve at all.
- Real 26.2 breaking change found and fixed: `Minecraft#screen` field, `Minecraft#setScreen(Screen)`, and `Minecraft#getToastManager()` were removed, moved onto `Minecraft#gui` (the `Gui` class) as `gui.screen()` / `gui.setScreen(Screen)` / `gui.toastManager()` (confirmed via `javap` on the 26.2 deobf jar). Fixed via a `current.parsed >= "26.2"` string-replacement rule in `stonecutter.gradle.kts` (scoped to lowercase `minecraft.setScreen(`/`minecraft.getToastManager()` so it doesn't touch the mod's own `Screens.setScreen(` helper), plus two explicit `/*? if >=26.2 {*/` branches in `client/ScreenImpl.java` for the two `Minecraft.getInstance()` call sites the regex couldn't reach.
- Bumped `mod_version` 4.0.5 → 4.1.0 → 4.1.1 (final: 4.1.1).

**Scoping findings (confirmed non-issues for this mod on 26.2):** no custom world/HUD rendering (`MultiBufferSource`/`LevelRenderer` untouched), `ResourceLocation`→`Identifier` already handled by an existing `>= "1.21.11"` rule, no individual colored item/block constants, no datagen/`FabricTagsProvider` usage, and all 14 `@Mixin` classes target networking/login/lifecycle classes rather than renderer classes.

**Investigated and reverted — not a port bug:** mid-testing, AutoModpack's self-updater appeared to downgrade itself to a stale 4.0.5 mid-launch. Root cause was external: the public Modrinth listing was still 4.0.5, and the production server the client was testing against was still hosting a 26.1 modpack — `SelfUpdater`'s server-pinned-version sync (`syncAutoModpackVersion`) faithfully matched both. A `syncAutoModpackVersion` default-disable was tried and then reverted once the actual cause was confirmed; the setting stays at its original default (`true`). The real unblock for testing the 26.2 client against a 26.1 server was clearing the local `selectedModpack` client config to skip modpack-sync entirely until the server itself is upgraded.

**Verified:** `:26.2-fabric:build` and `:26.2-neoforge:build` both pass cleanly, producing `automodpack-mc26.2-fabric-4.1.1.jar` and `automodpack-mc26.2-neoforge-4.1.1.jar`.
