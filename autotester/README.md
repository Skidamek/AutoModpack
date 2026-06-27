# AutoModpack Autotester

Docker-based in-game integration tests for AutoModpack. The runner starts a
real Minecraft server and a HeadlessMC client, drives the client UI through an
opt-in file bridge, and verifies that the modpack sync flow works end to end.

## Prerequisites

- Docker
- Python 3.11+
- `uv`
- Built AutoModpack artifacts in `merged/`

Build artifacts first from the repository root. Use `build` (which runs the jar
merge as a finalizer) rather than `mergeJar` directly — `mergeJar` on its own
does not rebuild the dependency modules. The `-Pautomodpack.autotest` flag bundles
the in-game test instrumentation (`AutoTestBridge` + the `dev` mixins) into the
jars; it is required for the autotester and is never included in release builds.

```bash
./gradlew build -Pautomodpack.autotest
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
  ref: "64d3c126e72bbfccf95e71afaa6536f50bc64097"  # branch, tag, or commit SHA
```

`ref` may be a branch, tag, or commit SHA; it is pinned to a SHA here for
reproducible image builds. This default ref carries the patch required to launch
**MC 26.2** headlessly
(stock HeadlessMC can't yet — its LWJGL stubs don't satisfy 26.2's new render
backend); it does not change behavior on other versions. Point `repo`/`ref` at
any other HeadlessMC build and rebuild the image
(`uv --project autotester run autotester build-images`). If they are unset, the
build falls back to upstream HeadlessMC (`headlesshq/headlessmc`).

## Scenarios

Scenarios are **declarative**: a `flow` is an ordered list of *steps*, where each
step is a verb plus arguments. Verbs are generic building blocks (click a button,
type into a field, wait for a condition, verify files), so most tests need no
Python — behavior is expressed entirely in YAML.

```yaml
id: download-only

flow:
  - use: boot               # a macro from scenarios/_lib.yaml
  - use: accept_certificate
  - use: download_modpack
  - do: verify_files        # a verb with arguments
    name: verify all synced files are present
  - do: quit

topology:
  server:
    memory: 2G

serverFiles:
  modpackName: amp-autotest
  marker: config/amp-autotest-marker.json
  files:
    - path: config/example.txt
      content: "hello\n"
```

A step is either a bare name (`- quit`, or a macro name) or a mapping with a
`do:` verb and its arguments. Optional keys on any step:

| Key | Meaning |
| --- | --- |
| `name` | Human-readable label shown in logs and `results.json`. |
| `when` | A condition; the step runs only if it holds. |
| `repeat` | Run the step N times. |
| `optional` | If the step fails, log it and continue instead of failing the run. |

### Verbs

| Verb | Purpose |
| --- | --- |
| `click` | Click the element matched by `select:` (defaults to enabled elements). Set `enable: true` to force-enable it first. |
| `type` / `paste` | Type `value:` into the field matched by `select:` (defaults to the first text field). |
| `wait_for` | Poll `until:` (a condition) until it holds or `timeout:` elapses. |
| `assert` | Fail immediately unless `that:` (a condition) holds. |
| `sleep` | Wait `duration:` (e.g. `2s`). |
| `wait_file` / `wait_files` | Wait for file(s) under the client game dir. |
| `verify_files` | Wait until every `serverFiles.files` entry exists in the synced modpack. |
| `verify_mods` | Wait until every `serverFiles.expectedMods` glob is present. |
| `launch_server` / `wait_server` | Start the server / wait for `Done (`. |
| `launch_client` / `wait_bridge` | Start a client / wait for its bridge. |
| `connect` / `disconnect` / `quit` | Drive the client connection. |
| `wait_client_exit` | Wait for the client container to exit (after a restart). |
| `wait_join` | Wait until the player is in-game (no screen open). |

### Selectors

`select:` (and the `element` condition) match GUI elements declaratively:

```yaml
select:
  role: button        # button | textfield | any (default)
  text: Verify        # exact match preferred, else substring (case-insensitive)
  text_any: [ok, yes] # any of these
  class: Btn          # substring of the element's class
  enabled: true       # filter by enabled / visible
  index: -1           # pick the Nth match (negative counts from the end)
```

### Conditions

`when:`, `wait_for.until:`, and `assert.that:` all take a condition. All keys in a
condition must hold (AND):

| Key | True when |
| --- | --- |
| `screen` / `screen_not` | The current screen class/title contains (any of) the given value(s). |
| `screen_none` | No screen is open (the player is in-game). |
| `element` / `no_element` | A selector matches at least one / no elements. |
| `file` / `file_gone` | A path under the game dir exists / does not exist. |
| `log` | A regex matches a container log; capture groups into vars (see below). |
| `all` / `any` / `not` | Combine sub-conditions. |

### Templating and variables

Strings expand `${...}` against the scenario context: `${target.id}`,
`${server.host}`, `${modpack}`, `${marker}`, plus any captured variables. The
`log` condition can capture regex groups into variables for later steps:

```yaml
- do: wait_for
  name: read fingerprint
  until:
    log:
      container: server
      matches: 'fingerprint[:\s]+([0-9A-Fa-f:]+)'
      capture: { fingerprint: 1 }
- do: type
  select: { role: textfield }
  value: "${fingerprint}"     # captured above
```

### Macros

Reusable step sequences live in `scenarios/_lib.yaml` and are referenced by name
(`- use: boot`, or inline as a bare string). A scenario can also define local
sequences under a `sequences:` key. Files starting with `_` are libraries and are
never run as scenarios. The shipped library provides `boot`,
`read_server_fingerprint`, `connect_to_server`, `accept_certificate`,
`download_modpack`, `restart_client`, and `rejoin`.

### Adding a new verb

Generic verbs cover most needs. When you genuinely need new behavior, register a
verb in `automodpack_autotester/engine/` (UI/filesystem verbs) or in `runner.py`
(verbs that touch Docker):

```python
from automodpack_autotester.engine.registry import verb

@verb("my_step")
def my_step(ctx, step):
    target = ctx.resolve(step["arg"])   # expand templates
    ctx.gui()                           # current GUI snapshot
    ctx.bridge.click(...)               # drive the client
```

Engine internals (selectors, conditions, templating, the executor) are covered by
`tests/` and run without Docker: `uv --project autotester run --group dev pytest`.

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
      "error": "step 'confirm download' failed: ...",
      "steps": [
        { "name": "start server", "verb": "launch_server", "ok": true, "duration": 0.1 },
        { "name": "confirm download", "verb": "click", "ok": false, "duration": 90.0, "error": "..." }
      ]
    }
  ]
}
```

Each step records its `name`, `verb`, `ok`, and `duration`; failed steps also
carry an `error`. On failure the partial step list up to and including the failing
step is preserved.

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
