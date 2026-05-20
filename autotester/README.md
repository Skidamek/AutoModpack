# AutoModpack Autotester

Docker-based in-game integration tests for AutoModpack. Spins up a real
Minecraft server + client, runs the full modpack sync flow, and verifies
everything works.

## How it works

Every test is defined by a **scenario** — a YAML file that lists **phases**
to execute in order. The framework orchestrates Docker containers
(server via itzg/minecraft-server, client via HeadlessMC) and drives
the in-game UI through a file-based JSON bridge.

The default scenario (`sync`) launches a server, starts a client,
runs the sync flow (fingerprint → download → verify → restart),
then launches a second client session to verify rejoin behaves correctly
(no re-download, player joins in-game immediately).

For each target, `run_case()` in `runner.py` creates an isolated Docker
network, starts a server + client container, executes the phase sequence,
captures logs, removes the containers, and returns a result dict that is
aggregated and written to `results.json`.

## Prerequisites

- Docker (tested with 27+)
- Python >= 3.11 + [uv](https://docs.astral.sh/uv/)
- Merged AutoModpack artifact in `merged/` (produced by `./gradlew mergeJar`)

## CLI reference

All commands run from the repo root:

```
uv --project autotester run autotester build-images [--client-image IMG] [--headlessmc-version VER]
uv --project autotester run autotester run        [--target ID | all] [--scenario ID] [--jobs N]
                                                  [--docker-uid UID] [--docker-gid GID]
                                                  [--artifact-dir PATH] [--out-dir PATH]
                                                  [--client-image IMG]
uv --project autotester run autotester clean      [--out-dir PATH]
```

(Or `cd autotester` and use `uv run autotester ...` instead.)
### build-images

Builds the client Docker image (Java + HeadlessMC).

| Flag | Default | Description |
|------|---------|-------------|
| `--client-image` | `settings.yaml → images.client` | Tag for the built image |
| `--headlessmc-version` | `settings.yaml → headlessmc.version` | HeadlessMC launcher version |

### run

Runs the selected target(s) against a scenario.

| Flag | Default | Description |
|------|---------|-------------|
| `--target` | `settings.yaml → run.target` (`all`) | Target ID from `targets.yaml`, or `all` |
| `--scenario` | `settings.yaml → run.scenario` (`sync`) | Scenario name (stem of `scenarios/*.yaml`) |
| `--jobs` | `settings.yaml → run.jobs` (`1`) | Max parallel containers (watch Docker resources) |
| `--docker-uid` | `AUTOTEST_DOCKER_UID` or `os.getuid()` | UID for client container process |
| `--docker-gid` | `AUTOTEST_DOCKER_GID` or `os.getgid()` | GID for client container process |
| `--artifact-dir` | `settings.yaml → paths.artifactDir` (`merged/`) | Directory with merged loader JARs |
| `--out-dir` | `settings.yaml → paths.outDir` (`autotester/out/`) | Output directory for logs and results |
| `--client-image` | `settings.yaml → images.client` | Client Docker image tag |

**Ctrl+C behavior:**
- First Ctrl+C cancels queued (not-yet-started) tests, waits for running
  containers to be cleaned up by their `finally` blocks, then writes partial
  `results.json` and exits with code 1.
- Second Ctrl+C calls `os._exit(1)` immediately (force kill).

### clean

Removes the output directory entirely.

## Output layout

Each run produces a timestamped directory under `--out-dir`:

```
<out-dir>/
├── results.json                       ← aggregated results (see below)
├── <target-id>-<unix-ts>-<random>/    ← one per test case (run_case)
│   ├── amp-s-<random>.log             ← server container logs
│   ├── amp-c-<random>.log             ← client container logs
│   ├── server/                        ← server game directory
│   │   ├── mods/automodpack.jar
│   │   ├── automodpack/automodpack-server.json
│   │   ├── automodpack/host-modpack/main/config/amp-autotest-marker.json
│   │   └── automodpack/host-modpack/main/config/amp-autotest-*.txt
│   └── client/
│       └── game/                      ← client game directory
│           ├── mods/automodpack.jar
│           ├── config/fml.toml        ← Forge/NeoForge only
│           └── automodpack/
│               ├── autotest/          ← bridge command/response files
│               └── modpacks/
│                   └── amp-autotest/  ← synced modpack (downloaded)
└── .hmc-cache/                        ← seeded HMC cache (persists between runs)
```

### results.json schema

Written when `run` completes (or after Ctrl+C). Structure:

```json
{
  "ok": false,
  "results": [
    {
      "target": "1.21.11-fabric",
      "scenario": "sync",
      "ok": false,
      "duration": 142.7,
      "error": "Timeout: marker file ... did not appear within 180s"
    },
    {
      "target": "1.21.11-neoforge",
      "scenario": "sync",
      "ok": true,
      "duration": 98.3
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `ok` | bool | `true` only if ALL results are ok |
| `results[].target` | string | Target ID from `targets.yaml` |
| `results[].scenario` | string | Scenario ID |
| `results[].ok` | bool | Did this test pass? |
| `results[].duration` | float | Wall-clock seconds for `run_case()` |
| `results[].error` | string? | Failure message (absent on pass) |

**Exit codes:** `0` = all passed, `1` = any failed or user interrupted.

## Phase reference

| Phase | Description | Timeout |
|-------|-------------|---------|
| `launch_server` | Start the server container (itzg/minecraft-server) | N/A |
| `launch_client` | Start/restart client container (HeadlessMC); removes existing container first if re-running | N/A |
| `read_fingerprint` | Extract TLS fingerprint from server logs (regex `certificate fingerprint[: ]+[0-9A-Fa-f:]+`) | `serverStartSeconds` (default 180s) |
| `wait_server` | Wait for `Done (` in server logs | `serverStartSeconds` (default 180s) |
| `wait_bridge` | Poll `bridge-state.json` for existence, then ping the bridge | `clientStartSeconds` (default 180s) |
| `click_continue` | Click "Continue" on Forge/NeoForge welcome/snooper screens until TitleScreen appears | 30s |
| `connect` | Bridge `connect` to server; retries on failure (TitleScreen → retry, ConnectScreen stuck → retry, other → success) | 90s |
| `wait_fingerprint` | Wait for `FingerprintVerificationScreen` to appear | 180s |
| `accept_fingerprint` | Type fingerprint into EditBox, click Verify, wait for DangerScreen/DownloadScreen/RestartScreen | 20s |
| `skip_fingerprint` | Skip verification (for versions without EditBox): click Skip, type "I accept the risk", click active Skip button | 30s |
| `wait_danger` | Wait for `DangerScreen` to appear | 90s |
| `click_confirm` | Click the last active Button on current screen (Confirm on DangerScreen) | 5s |
| `wait_download` | Wait for modpack marker file to appear under `modpacks/{name}/` | `downloadFileSeconds` (default 180s) |
| `verify_files` | Check all `serverFiles.files[]` exist under synced modpack | 120s |
| `verify_mods` | Check all `serverFiles.expectedMods[]` glob patterns match installed jars | 120s |
| `click_restart` | If RestartScreen appears within 20s, click Restart/Close/Quit button, wait for client exit | wait_exit: 90s |
| `quit` | If container is running, bridge `quit` command | bridge: 30s |
| `wait_join` | Poll for null `screenClass` (player in-game, no modal screens) | `rejoinSeconds` (default 90s) |

## Bridge protocol

The autotester drives Minecraft's UI through a file-based JSON bridge.
The bridge reads commands from a file and writes responses.

**Request:** `{game_dir}/automodpack/autotest/bridge-command.json`
```json
{"token": "...", "op": "get_screen"}
{"token": "...", "op": "click", "widgetId": 42}
{"token": "...", "op": "click", "selector": {"text": "Continue"}}
{"token": "...", "op": "set_text", "selector": {"type": "EditBox", "index": 0}, "text": "AB:CD:..."}
{"token": "...", "op": "verify_fingerprint", "fingerprint": "AB:CD:..."}
{"token": "...", "op": "connect", "host": "amp-s-...", "port": 25565}
{"token": "...", "op": "set_screen"}
{"token": "...", "op": "get_widgets"}
{"token": "...", "op": "quit"}
{"token": "...", "op": "ping"}
```

**Response:** `bridge-response.json`
```json
{"ok": true, "screenClass": "FingerprintVerificationScreen", "widgets": [...]}
{"ok": false, "error": "No such widget"}
```

The command file is written atomically (`.tmp` + `rename`). The client
polls for it, executes `op`, and writes the response. The Python bridge
client (`bridge.py → BridgeClient.request()`) polls for the response
file for up to 30 seconds, sleeping 50ms between polls.

Available operations:

| `op` | Payload | Response |
|------|---------|----------|
| `ping` | — | `{"ok": true}` |
| `get_screen` | — | `{"ok": true, "screenClass": "..."}` |
| `get_widgets` | — | `{"ok": true, "screenClass": "...", "widgets": [{"id": N, "type": "...", "text": "...", "active": bool}]}` |
| `click` | `widgetId` or `selector` (`{text: "..."}`) | `{"ok": true}` |
| `set_text` | `selector` + `text` | `{"ok": true}` |
| `set_screen` | — | Clear the current screen (used to abort a stuck connection) |
| `connect` | `host`, `port` | `{"ok": true}` |
| `verify_fingerprint` | `fingerprint` | `{"ok": true}` — types fingerprint + clicks Verify |
| `quit` | — | `{"ok": true}` — calls `Minecraft.getInstance().stop()` off the main thread |

## Scenario YAML reference

```yaml
# Required
id: my-test                          # Scenario identifier
flow:                                # Phase sequence (ordered)
  - launch_server
  - wait_server
  - launch_client
  - wait_bridge
  - ...

# Optional
description: |                       # Human-readable (not used by code)
  What this scenario does

topology:
  server:
    type: FABRIC                     # Override engine type (default: settings.yaml → serverTypes)
    image: itzg/minecraft-server     # Override server image
    memory: 4G                       # Container memory (default: 2G)
    env:                             # Extra env vars (merged with settings.yaml → server.env)
      ENABLE_ROLLING_LOGS: "false"
    modrinth:
      projects:                      # Modrinth project slugs (ferrite-core? = optional dependency)
        - ferrite-core?
      projectsByLoader:              # Per-loader project list
        fabric: [sodium]
      version: "1.21"                # Modrinth version filter
      versionType: release           # Modrinth version type
      dependencies: true             # Auto-include dependencies
    serverCache:
      enabled: true                  # Default: true
      clean: false                   # Purge volume before each run

serverFiles:
  modpackName: amp-autotest          # Modpack namespace
  marker: config/amp-autotest-marker.json   # Path that signals "sync complete"
  expectedMods:                      # Glob patterns for verify_mods phase
    - "ferritecore*.jar"
  files:                             # Files written to host-modpack/main/ (synced to client)
    - path: config/test-file.txt
      content: "hello\n"

timeouts:                            # Override settings.yaml timeouts per-scenario
  serverStartSeconds: 300
  clientStartSeconds: 300
  downloadFileSeconds: 300
  rejoinSeconds: 180
```

### scenarios/ available

| File | ID | Flow summary |
|------|----|-------------|
| `sync.yaml` | `sync` | Full end-to-end: server+boot → fingerprint → download → verify → restart → rejoin → in-game check |
| `download-only.yaml` | `download-only` | Same as sync but skips restart and rejoin (faster debug iteration) |

## settings.yaml reference

```yaml
paths:
  artifactDir: merged                # Artifact directory (relative to repo root)
  outDir: autotester/out             # Test output directory

images:
  server: itzg/minecraft-server      # Server Docker image
  client: automodpack-autotest-client:local  # Client Docker image
  serverTagTemplate: "java{java}"    # Tag template (e.g. itzg/minecraft-server:java21)

run:
  target: all                        # Default --target
  scenario: sync                     # Default --scenario
  jobs: 4                            # Default --jobs
  retryMax: 0                        # Not implemented (reserved)

server:
  memory: 2G                         # Default container RAM
  env:                               # Default env vars for itzg/minecraft-server
    EULA: "TRUE"
    ONLINE_MODE: "FALSE"
    DIFFICULTY: "peaceful"

serverTypes:                         # Maps loader → TYPE env var
  fabric: FABRIC
  forge: FORGE
  neoforge: NEOFORGE

headlessmc:
  version: "2.9.0"                   # HeadlessMC launcher version in client image

timeouts:
  serverStartSeconds: 180            # Max wait for server "Done ("
  clientStartSeconds: 180            # Max wait for bridge to appear
  downloadFileSeconds: 180           # Max wait for download marker
  rejoinSeconds: 90                  # Max wait for in-game rejoin

serverCache:                         # Docker volume for server JARs
  enabled: true
  volumePrefix: "amp-server-cache"   # Volume name: {prefix}-{target.id}
  clean: false

automodpack:
  config:                            # Written to server's automodpack-server.json
    DO_NOT_CHANGE_IT: 2
    modpackHost: true
    generateModpackOnStart: true
    syncedFiles:
      - "/mods/*.jar"
      - "/kubejs/**"
      - "!/kubejs/server_scripts/**"
    ...
```

## targets.yaml reference

```yaml
defaults:
  fabricLoader: "0.17.3"             # Default Fabric loader version
  java: 21                           # Default Java version

targets:
  - id: "1.21.11-fabric"             # Unique target ID (used with --target)
    minecraft: "1.21.11"             # Minecraft version
    loader: "fabric"                 # fabric | forge | neoforge
    java: 21                         # Java version (17 | 21 | 25)
    fabricLoader: "0.17.3"           # Fabric loader version (required for fabric)
    forgeVersion: "47.3.0"           # Forge version (required for forge)
    neoforgeVersion: "21.11.37-beta" # NeoForge version (required for neoforge)
```

Artifact discovery: the runner globs `{artifactDir}/automodpack-mc{minecraft}-{loader}-*.jar`
and uses the newest match.

## Docker networking

Each test case creates an isolated Docker bridge network. The server is
reachable from the client by its container name. The network and both
containers are destroyed in the `finally` block of `run_case()`.

### Client container (HeadlessMC)

Built via `build-images`. Uses `run-headlessmc-client` entrypoint which:
1. Selects the correct Java binary (`java-{version}-openjdk-amd64`)
2. Downloads HeadlessMC launcher if missing
3. Launches Minecraft with AutoModpack

Environment:

| Variable | Description |
|----------|-------------|
| `AM_AUTOTEST_BRIDGE_TOKEN` | Auth token; must match bridge request `token` |
| `AM_AUTOTEST_GAME_DIR` | Path to client game directory (mounted from host) |
| `AM_AUTOTEST_HMC_DIR` | HeadlessMC cache directory (mounted from host) |
| `AUTOTEST_DOCKER_UID` | Container UID (for file ownership) |
| `AUTOTEST_DOCKER_GID` | Container GID (for file ownership) |

### Server container (itzg/minecraft-server)

Uses a Docker volume (`amp-server-cache-{target.id}`) to persist server JARs
between runs. The host `mods/` and `automodpack/` directories are bind-mounted
into `/data/mods` and `/data/automodpack` so the server sees the test's
automodpack.jar and config.

## Caching

- **Server JARs**: Docker volume `amp-server-cache-{target.id}`. Disable by
  setting `serverCache.enabled: false` in `settings.yaml`.
- **Client HMC cache**: The `.hmc-cache/` directory is seeded from the
  previous run's cache via `cp --reflink=auto` before each test, avoiding
  re-download of Minecraft jars. It lives at `{out_dir}/../.hmc-cache/`.

## Environment variables

| Variable | Default | Used in |
|----------|---------|---------|
| `AUTOTEST_DOCKER_UID` | `os.getuid()` | Client container `-u` flag |
| `AUTOTEST_DOCKER_GID` | `os.getgid()` | Client container `-u` flag |

Set these when running as root or inside a CI container where uid/gid
mismatches would cause permission issues on bind-mounted volumes.

## Adding a target

1. Add an entry to `targets.yaml` with `id`, `minecraft`, `loader`,
   `java`, and the appropriate loader version field.
2. The `build-images` step is already version-agnostic — any Java version
   in the matrix will work as long as it's between 17 and 25 and available
   in the container (`java-{version}-openjdk-amd64`).
3. Add the target ID to `.github/workflows/ingame-tests.yml` matrix to
   run it in CI.

## Adding a scenario

Create `scenarios/my-test.yaml`:

```yaml
id: my-test
flow: [launch_server, wait_server, launch_client, wait_bridge, quit]
topology:
  server:
    modrinth:
      projects: [ferrite-core]
      dependencies: true
```

Then run: `uv --project autotester run autotester run --scenario my-test`

## CI (GitHub Actions)

`.github/workflows/ingame-tests.yml` runs each target as a separate job
in a matrix. Each job: checks out repo → Build AutoModpack → Build client
image → Run autotest → Upload artifacts. Artifacts include the full
`autotester/out/` directory (logs + results.json). The workflow can be
triggered manually via `workflow_dispatch` with overridable `scenario`,
`target`, and `jobs` inputs.
