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
  repo: "https://github.com/headlesshq/headlessmc.git"
  ref: "2.10.0"  # branch, tag, or commit SHA
```

`ref` may be a branch, tag, or commit SHA; the default uses the immutable
upstream 2.10.0 release for reproducible image builds and Minecraft 26.2
support. Point `repo`/`ref` at another HeadlessMC build and rebuild the image
with `uv --project autotester run autotester build-images`.

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

### Networking, modes, and scoping

Three scenario-header keys decouple *how* a scenario runs from *what* it tests:

| Key | Values | Meaning |
| --- | --- | --- |
| `network` | `bridge` (default) / `host` | Container transport. `bridge` wires a per-run user network (CI). `host` puts both containers in the host network namespace (the client reaches the server on `localhost`) — the only topology a host-network-only sandbox allows. Run host transport with `--jobs 1`: host-mode servers all bind `25565` and would collide. Also settable globally via `network:` in `settings.yaml`. |
| `mode` | `full` (default) / `client-only` | `full` launches a server + client. `client-only` launches **only** the client against a pre-staged modpack — no server, no certificate/download/restart dance. |
| `targets` / `loaders` / `minecraft` | list (globs where noted) | Scope the scenario to compatible targets. A target must pass every key present: `targets` (glob on id), `loaders` (exact), `minecraft` (glob). Out-of-scope targets are skipped with a `SKIP` line instead of failing on missing mods. |

#### Client-only (offline / pre-staged) mode

`mode: client-only` is the fast loader-debugging path: stage a modpack, boot just
the client, and assert on the launch log — seconds per iteration instead of a
multi-minute gameplay round trip. The `stage_modpack` verb lays the modpack into
`automodpack/modpacks/<name>/` and writes a client config that selects it with
`updateSelectedModpackOnLaunch=false`, so the client loads it on boot without
contacting a server:

```yaml
mode: client-only

flow:
  - use: boot_client_only        # stage_modpack + launch_client
  - do: wait_for
    until: { log: { matches: 'AutoModpack prelaunched' } }
  - do: assert
    that:
      log:
        matches_all: [ 'Prelaunching AutoModpack', 'AutoModpack prelaunched' ]
        not_matches: [ 'ClassNotFoundException' ]
  - do: wait_exit                 # tolerate a later headless crash OR a GPU idle
    expect: any
    or_alive: true
```

`stage_modpack` accepts `from:` (a ready modpack dir to copy wholesale), `mods:`
(extra jars to drop into the pack's `mods/`), and `config:` (extra client-config
overrides). **`from:` and `mods:` paths resolve against the repo root** (the
parent of `autotester/`) unless absolute. The combination of whole-log assertions
and `wait_exit: { expect: any, or_alive: true }` makes "verify it loaded, don't
care what happens at render" robust on both headless and GPU hosts. See
`scenarios/client-loads-offline.yaml`.

### Discovering verbs and validating scenarios

```bash
autotester verbs                       # list verbs + condition keys (from the registry)
autotester validate                    # statically check every scenario
autotester validate --scenario sync    # check one
```

`validate` expands macros and checks that every verb/macro name resolves and
every condition uses known keys — catching typos in seconds instead of after
minutes in Docker. `run` performs the same check before launching containers and
aborts on a malformed scenario.

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
| `stage_modpack` | Pre-stage a modpack into the client game dir for offline/client-only runs (see below). |
| `connect` / `disconnect` / `quit` | Drive the client connection. |
| `wait_exit` | Wait for the client container to exit. `expect: any\|clean\|crash` asserts how it exited (default `any`). `or_alive: true` tolerates the client still running after the grace period (for "loaded then idles" on a GPU host). `wait_client_exit` is an alias. |
| `wait_join` | Wait until the player is in-game (no screen open). |

Run `autotester verbs` to print this list (with one-line docs) and the valid
condition keys, generated straight from the registry — no need to grep `@verb(`.

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
| `log` | A regex matches a log source — container stdout or a game-dir file (see below). |
| `all` / `any` / `not` | Combine sub-conditions. |

#### The `log` condition

`log` matches against a **source** (a container's stdout, or a file artifact in
the game dir) with positive, negative, and quantified matchers. By default it
scans the **whole** log, so an early line can't be silently scrolled out of a
tail window:

```yaml
log:
  container: server | client   # source: container stdout (default: client)
  file: logs/debug.log          # OR a file under the client game dir
  tail: 100000                  # only the last N lines (default: the whole log)
  matches: <regex>              # must be present
  matches_all: [<regex>, ...]   # every one must be present
  matches_any: [<regex>, ...]   # at least one must be present
  not_matches: <regex> | [...]  # none may be present
  count: N                      # `matches` must occur exactly N times
  min_count: N                  # ... at least N times
  max_count: N                  # ... at most N times
  capture: { var: group }       # capture a group of `matches` into a var
```

Targeting `file:` lets you assert on the rich evidence in `logs/debug.log`
(TRACE/Mixin lines) that never reaches stdout. `not_matches` expresses
"this error no longer appears"; the count keys quantify occurrences.

### Templating and variables

Strings expand `${...}` against the scenario context: `${target.id}`,
`${server.host}` (the complete `host:port` address), `${modpack}`, `${marker}`, plus any captured variables. The
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
