# AutoModpack Autotester

Docker-based in-game integration tests for AutoModpack. The runner starts a
real Minecraft server and a HeadlessMC client, drives the client UI through an
opt-in file bridge, and verifies that the modpack sync flow works end to end.

## Prerequisites

- Docker
- Python 3.11+
- `uv`
- Built AutoModpack artifacts in `merged/`

Build artifacts first from the repository root:

```bash
./gradlew mergeJar
```

## Quick Start

Build the client image:

```bash
uv --project autotester run autotester build-images
```

Run one target:

```bash
uv --project autotester run autotester run --target 1.21.11-fabric --scenario download-only
```

Run the full default matrix:

```bash
uv --project autotester run autotester run --target all --scenario sync --jobs 1
```

Clean generated output:

```bash
uv --project autotester run autotester clean
```

## What It Tests

The default `sync` scenario performs this flow:

1. Start a server container.
2. Start a client container.
3. Connect to the server.
4. Accept the server certificate fingerprint.
5. Download and verify synced files.
6. Restart the client.
7. Rejoin and verify the player reaches the game.

The `download-only` scenario stops after the first sync and file verification.
Use it for faster debugging when restart/rejoin behavior is not relevant.

## Configuration

- `settings.yaml` contains default paths, Docker images, server config,
  timeouts, and runner defaults.
- `targets.yaml` lists Minecraft/loader/Java combinations.
- `scenarios/*.yaml` defines test flows and scenario-specific server files.

Common run options:

| Option | Description |
| --- | --- |
| `--target ID` | Target from `targets.yaml`, or `all`. |
| `--scenario ID` | Scenario file stem from `scenarios/`. |
| `--jobs N` | Number of targets to run in parallel. |
| `--artifact-dir PATH` | Directory containing merged AutoModpack jars. |
| `--out-dir PATH` | Directory for logs and `results.json`. |
| `--client-image IMG` | Docker image used for HeadlessMC clients. |

### HeadlessMC build

The client image builds its HeadlessMC launcher from the git repo and ref in
`settings.yaml`:

```yaml
headlessmc:
  repo: "https://github.com/Skidamek/headlessmc.git"
  ref: "mc26.2-headless"
```

This default ref carries the patch required to launch **MC 26.2** headlessly
(stock HeadlessMC can't yet — its LWJGL stubs don't satisfy 26.2's new render
backend); it does not change behavior on other versions. Point `repo`/`ref` at
any other HeadlessMC build and rebuild the image
(`uv --project autotester run autotester build-images`). If they are unset, the
build falls back to upstream HeadlessMC (`headlesshq/headlessmc`).

## Scenarios

A scenario is an ordered list of registered phases plus optional topology and
server-file configuration:

```yaml
id: download-only

flow:
  - launch_server
  - launch_client
  - read_fingerprint
  - wait_server
  - wait_bridge
  - connect
  - wait_fingerprint
  - accept_fingerprint
  - wait_download_prompt
  - confirm_download
  - wait_download
  - verify_files
  - quit

topology:
  server:
    memory: 2G
    env:
      ENABLE_ROLLING_LOGS: "false"

serverFiles:
  modpackName: amp-autotest
  marker: config/amp-autotest-marker.json
  files:
    - path: config/example.txt
      content: "hello\n"
```

Useful phases:

| Phase | Purpose |
| --- | --- |
| `launch_server` | Start the Minecraft server container. |
| `wait_server` | Wait until the server logs `Done (`. |
| `launch_client` | Start a HeadlessMC client container. |
| `wait_bridge` | Wait for the in-game bridge to report the client is ready. |
| `connect` | Connect the client to the test server. |
| `read_fingerprint` | Extract the AutoModpack TLS fingerprint from server logs. |
| `wait_fingerprint` | Wait for a certificate prompt with a text field and Verify button. |
| `accept_fingerprint` | Enter the expected fingerprint and click Verify. |
| `skip_fingerprint` | Skip certificate verification (accept the risk) instead of verifying. |
| `wait_download_prompt` | Wait for the modpack download/confirmation prompt. |
| `confirm_download` | Click the download button to start the sync. |
| `wait_download` | Wait for the marker file in the synced modpack. |
| `verify_files` | Verify all configured `serverFiles.files` exist on the client. |
| `verify_mods` | Verify all configured `serverFiles.expectedMods` exist on the client. |
| `confirm_restart` | Click restart/quit on the restart screen if shown. |
| `wait_join` | Verify the client reaches the in-game state. |
| `quit` | Stop the client through the bridge. |

## Output

By default, output is written to `autotester/out/`.

Important files:

- `results.json`: aggregated pass/fail result.
- `<case>/amp-s-*.log`: server container log.
- `<case>/amp-c-*.log`: client container log.
- `<case>/client/game/automodpack/modpacks/`: synced client modpacks.

`results.json` has this shape:

```json
{
  "ok": false,
  "results": [
    {
      "target": "1.21.11-fabric",
      "scenario": "sync",
      "ok": false,
      "duration": 142.7,
      "error": "Download marker file ... did not appear"
    }
  ]
}
```

## CI Workflow

`.github/workflows/ingame-tests.yml` is manual (`workflow_dispatch`). It builds
the normal project artifacts, builds the autotest client image, runs the chosen
target/scenario matrix, uploads per-target logs, and publishes an aggregate
summary.

## Bridge

The bridge is disabled unless the JVM property `automodpack.autotest=true` is
present. Test containers pass a per-run token and game directory through JVM
properties. Commands and responses are JSON files under:

```text
<gameDir>/automodpack/autotest/
```

The bridge is intentionally small and generic. It exposes operations for
`ping`, `gui`, `click`, `text`, `connect`, `disconnect`, and `quit`; the runner
builds scenario behavior from those primitives.
