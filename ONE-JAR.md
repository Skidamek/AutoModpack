# One jar

Selector outer (Java 17, all loaders) plus **one** nested game impl (mapped Minecraft, one Stonecutter target). Not one class set. Not Forgix relocate.

The live file in `mods/` is this jar. After a supported launcher swaps MC/loader, the same file still boots, then syncs the pack.

FAPI-free is a prerequisite for these sizes. Launcher version switch is a second project.

Receipts: current 22 `merged/` jars, FAPI stripped, assets once, packed onto a 1.20.1-fabric outer. One impl STORE jar **862–889 KB** (avg 881 KB). Solid uncompressed concat **19.4 MB**. CPU times are this machine; ~5–15× on an old dual-core.

A `byte[]` is not a mod. Knot needs a `file:` Path (`toRealPath` + `URLClassLoader`). Forge/Neo `jij:` can wrap a nested **zip** entry, not a zstd blob. The cache directory **is** that Path.

---

## Shared rules

Outer zip: STORE or DEFLATE only. ZIP method 93 is unreadable on Java 17.

Hide every impl from `fabric.mod.json` `jars` and JarJar `metadata.json`. Drop outer `depends.automodpack_mod`. Outer id `automodpack`. Impl id `automodpack_mod`.

Detect (Java 17, no Minecraft types):

- Loader: trampoline (`FabricLoader` / Forge SPI / NeoForge SPI), same idea as `ModpackLoader15` vs `16`.
- MC version: Fabric `minecraft` container / Forge `--fml.mcVersion` (`EARLY_MC_VERSION` already set before `new Preload()`).
- Id = Stonecutter spelling (`1.20.1-fabric`), resolved by matching the live MC version against the manifest's per-target covered versions (`26.1.2` -> `26.1-fabric`). Uncovered versions crash with the coverage list.

Inject **before** `new Preload()` (same window pack `addMod` already uses):

| Loader | Hook | With a `file:` Path to the selected impl |
|---|---|---|
| Fabric 0.16 | `FabricLanguageAdapter()` | `ModCandidateImpl.createPlain(List.of(path), meta, false, List.of())` then existing `addMod` |
| Forge fml40/47 | `LazyModLocator.getMainMod()` | `createMod(path)` instead of `jij:` on `META-INF/jarjar/automodpack-mod.jar` |
| NeoForge | `LazyModLocator.scanMods` | `SecureJar.from(path)` / `pipeline.addPath` instead of JarJar metadata |

Assets live on the outer once. Impl = mixins, init, AW/AT, no FAPI, no assets. 26.2 impls stay class-file 69; only mounted on Java 25.

---

## A — solid zstd + per-instance generation cache (recommended)

This is the 2.96 MB file. The 19 MB inflate happens **on cache miss**, not every boot. Miss fills **all 22** impl jars. After that, switching MC/loader in this instance is a git-stat of the working tree plus opening one 881 KB file.

You cannot populate those files without inflating once. “No inflate” is the **hit** path.

### Packed layout

```
automodpack.jar
  fabric.mod.json / mods.toml / neoforge.mods.toml
  locators / amp_libs / core / assets/automodpack/
  impl/manifest.bin     STORE
  impl/all.zst          STORE, zstd-19 of 22 STORE jars concatenated
```

Build `zstd -19` from a file so the frame header carries uncompressed size. Runtime: aircompressor `ZstdDecompressor`. Do not compress with airlift. `jar-optimizer` must leave `impl/*` STORE.

`impl/manifest.bin` little-endian (source of truth, SHA-1 like the rest of the pipeline):

```
magic        u32  'AMP1'
generation   20 bytes  SHA-1 of all.zst
count        u16
repeat count:
  id_len     u16
  id         utf-8     // "1.20.1-fabric"
  ver_count  u16
  versions   ver_count x (u16 len + utf-8)  // "26.1\n26.1.1\n26.1.2" - the covered MC versions,
             // from the target group's publish_versions; the runtime resolves the live MC version
             // against this list (26.1.2 -> 26.1-fabric). Uncovered version = crash, never a guess.
  off        u32       // offset into uncompressed solid
  len        u32       // STORE jar length
  sha1       20 bytes  // SHA-1 of that STORE jar
```

`generation` is the cache key. Self-update that changes `all.zst` changes generation → immediate miss.

### Instance working tree

Not the global data root. This is a worktree derived from **this instance’s** `mods/` jar, same split as `StoragePaths`: human-edited at the top of `automodpack/`, runtime under `client/` / `server/`.

```
automodpack/client/impl-cache/     // client
automodpack/server/impl-cache/     // dedicated server
  stamp.json                       // { generation } - the manifest entries themselves are the file list
  1.18.2-fabric.jar
  …
  26.2-neoforge.jar
```

~19.4 MB of jars on disk per instance, per AutoModpack generation. Minecraft instances already dwarf that.

Global CAS would share the 19 MB across Prism instances on one user. Do not. FileMetadataCache records are path-keyed; two instances with different AM versions would fight a shared directory of *names*. Pandora sandbox may not see the global data root. Version switch is per instance. Keep the bytes next to this instance’s other runtime.

Stat records themselves can still live in the existing `FileMetadataCache` (path-keyed by absolute path). That is Git’s index: one cache of stats, many worktrees.

### Hit (every later launch, including MC switches)

Loader already opened the outer jar. Read `impl/manifest.bin` (~1 KB).

1. `stamp.generation == manifest.generation`. Else miss.
2. For **every** id in the manifest: `FileIntegrity.matchesNamed(cacheDir.resolve(id + ".jar"), size, sha1, fileMetadataCache)`.
   - That is `FileMetadataCache.matchesImmutable`: `ce_match_stat` (size, mtime, ctime, creation, inode/fileKey). **No content read.** Racy mtime is not tamper; disturbed stat is.
3. Any miss → **invalidate the whole directory immediately**. Do not salvage siblings. Regenerating one slice still decodes the whole solid frame, so partial repair is the 19 MB miss with extra code.
4. All hit → `cacheDir.resolve(id + ".jar")` is the mount Path.

22 stats in one directory is noise on HDD. Do not skip the sibling stats: a half-written tree must not look like a hit.

### Miss

1. Delete `impl-cache/` (or ignore it).
2. Read `impl/all.zst` (538 KB), inflate to 19.4 MB (9 ms here / ~50–140 ms old CPU).
3. Slice each jar by `off`/`len`. Verify each slice’s SHA-1 against the manifest **before** publish. Mismatch = corrupt outer jar, hard crash.
4. Write into `impl-cache/.staging/` (22 files + `stamp.json`). `FileMetadataCache.overwriteCache` each file with the known SHA-1 (we just hashed the slice).
5. Atomic publish: rename staging over `impl-cache/` (same directory-replace pattern as other client runtime). A crash mid-write never becomes a stamp hit.
6. Drop the 19 MB array. Mount `impl-cache/<id>.jar`.

First boot of this AutoModpack version in this instance pays inflate + ~19 MB HDD write. Next boot, and every loader/MC swap while the jar is unchanged, does not.

### Why not cache only the selected jar

The product is switching versions in one instance. Caching one jar makes every new target another inflate. With a solid frame that inflate is 19 MB again. Filling all 22 on the first miss makes later switches free.

---

## B — zstd, one frame per target

```
impl/manifest.bin
impl/<id>.jar.zst     STORE, zstd-19 of that STORE jar only
```

Cold: read ~205 KB, inflate 881 KB (~2 ms / ~10–30 ms old CPU). Packed **6.94 MB**. Cache can still be per-instance, but then you either lazy-fill one jar per target (MC switch still inflates) or inflate all 22 frames on first miss (22 × 2 ms, 22 reads, same 19 MB disk as A, **larger** packed file).

A beats B on packed size and on “first miss then all switches are free.” B beats A only on **first-ever** miss CPU (1 MB vs 19 MB). After one successful miss, they are the same hit path.

---

## C — unique CAS objects

6.82 MB unique bytes. Packed ~2.9–5.1 MB. Cold either inflates 6.8 MB or does hundreds of seeks, then rebuilds a jar. Does not beat A+cache. Skip.

---

## D — no zstd: 22 jars, outer DEFLATE

Packed **8.12 MB**. `ZipFile` zlib-inflates one ~259 KB entry. Forge may `jij:` that nested path and skip a cache file; Fabric still needs `file:`. Same generation-cache idea works (miss = inflate 22 zlib entries, ~22 × 1 ms). No aircompressor. Larger download, same 19 MB worktree.

---

## E — relocate every target into the outer classpath

Packed **10.3 MB**. Mixin/AW/entrypoint landmines. Reject.

---

## Score

Weights: hit-path on HDD/DDR2 (version switch) > packed size > first miss > simple.

| | Packed | First miss | Hit (incl. MC switch) | RAM on miss | Score |
|---|---|---|---|---|---|
| **A solid + generation cache** | **2.96 MB** | 538 KB read, 19 MB inflate, 19 MB write | stamp + 22 × git-stat, open 881 KB | 19 MB once | **1** |
| B 22 × zstd-19, lazy one-jar cache | 6.94 MB | 205 KB + 881 KB inflate | open 881 KB | ~1 MB | 2 (cheaper first miss, every new target pays, 2× download) |
| D 22 × DEFLATE | 8.12 MB | one zlib or 22 on full fill | cache / Forge `jij` | ~1 MB | 3 |
| C unique CAS | 2.9–5.1 MB | 6.8 MB inflate or 140 seeks | only after rebuild | 7 MB | 4 |
| E relocate | 10.3 MB | none extra | n/a | none | 5 |

**Ship A with the generation cache.** The size target (5 MB, receipt 2.96 MB) and the version-switch goal are the same design: pay 19 MB once per instance per AutoModpack generation.

B if we refuse a 19 MB miss on the weakest HDD. That is a first-boot policy, not a hit-path problem.

Audit tripwire **5 MB** (receipt 2.96 MB).

---

## A runtime

```
manifest = read ZipFile(self) "impl/manifest.bin"
id = mcVersion + "-" + loader
dir = client ? automodpack/client/impl-cache : automodpack/server/impl-cache

if stamp.generation == manifest.generation
   && every FileIntegrity.matchesNamed(dir/<id>.jar, size, sha1, fileMetadataCache):
    mount = dir/<id>.jar
else:
    wipe dir
    solid = zstdDecompress(read "impl/all.zst")          // 19.4 MB, then drop
    staging = dir/.staging
    for each entry in manifest:
        slice = solid[off, len]
        if sha1(slice) != entry.sha1: crash
        atomicWrite(staging/<id>.jar, slice)
        fileMetadataCache.overwriteCache(staging/<id>.jar, entry.sha1)
    write stamp { generation, files }
    publish staging → dir                         // rename
    mount = dir/<id>.jar

addMod/createMod(mount)
new Preload()
```

`matchesNamed` / `matchesImmutable` never reads file bytes. Disturbed git-stat → false → miss → wipe. Do not rehash to “fix” a racy file; the next miss restages from `all.zst`.

---

## FAPI (Fabric) — DONE, shipped differently than first sketched

JiJ'd today: `api-base`, `registry-sync-v0`, `networking-api-v1`, `command-api-v1/v2`, `resource-loader-v0` (+ `v1` on 1.21.9+). Login networking is already a local copy. `FabricLoginMixin` stays `@Pseudo` untouched (guards against co-installed FAPI).

No pack-finder mixin. A resource-pack mount for our assets — ours or FAPI's — participates in pack stacking, so a server-pushed pack could override `automodpack.*` lang keys, textures and sounds and puppeteer our UI (social engineering). All three asset classes are self-served instead, on every loader:

- Text: core `L10n` resolves `assets/automodpack/lang/<locale>.json` from our jar (`code -> base -> en_us -> key`); impl `VersionedText.text()` returns literals, vanilla keys stay on vanilla translation. Server command feedback resolves server-side (`en_us`).
- Sound: `AudioManager` decodes the bundled ogg with Minecraft's own decoder (`OggAudioStream` <1.21.1, `JOrbisAudioStream` >=1.21.1 — both expose `AudioStream`: `getFormat` + `read`) into resident PCM and loops it on a `javax.sound` line; no `SoundEvent` registration anywhere, so no registry-sync and no registry delta joining servers. In-game only — decoder classes do not exist during preload.
- Textures: `ClientTextures` registers our sprites as `DynamicTexture`s under the ids the draw sites blit (lazy, render thread); `VersionedPanels` hand-nine-slices the bundled tooltip panels on every version (no vanilla-atlas sprite give on 26.1+).
- Commands: fabric-gated `CommandsMixin` injects at the `net.minecraft.commands.Commands` constructor RETURN and calls our `Commands.register` (Forge/Neo keep their events; `MixinPlugin.shouldApplyMixin` gates `mixin.fabric.` on the loader being fabric).

## Launcher switch

`LauncherVersionSwapper` only rewrites loader version, same loader type. Prism `mmc-pack.json` can also set `net.minecraft` and replace the loader uid. Java 17/21/25 must move with the instance. Pandora is `preferred_loader_version` until measured. Vanilla/CurseForge/Modrinth: install-only.

After A’s cache is filled, that swap does not touch zstd.

## Sequence

1. Remove FAPI on every Fabric target.
2. Unify the outer trampoline (all locator generations, three metadata files). Still one nested `automodpack-mod.jar` per published target.
3. Implement **A**: `impl/manifest.bin` + `impl/all.zst` + per-instance `impl-cache/` + git-stat via `FileIntegrity.matchesNamed`. Audit 5 MB.
4. Prism MC + loader-type + Java swap.

## Landmines

- Any miss invalidates the **generation**, not one file. Partial trees are not a hit.
- Staging rename must be the publish; a half-written `impl-cache/` without a matching stamp is a miss.
- `overwriteCache` after write so the first hit does not seed `matchesImmutable` from size-only (the no-record branch). We already know the SHA-1.
- Forge `IModLocator` must not reference NeoForge types.
- Mixin json lives inside the impl, never the outer.
- `Constants.MC_VERSION` / `LOADER` are runtime reads in the outer.
- Connector: native loader only until tested.
- Self-update changes `generation` → miss → refill. One SHA on Modrinth once A ships.
