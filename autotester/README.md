# AutoModpack autotester

In-game integration tests. Each case starts a real Minecraft server and a HeadlessMC
client, drives the client through a file bridge, and checks the sync flow end to end.
Scenarios are YAML; the engine is Python.

## Requirements

- A JDK for every Java version you run (`targets.yaml`)
- Docker, for the client image and for seeding a server tree
- Python 3.11+ and `uv`
- Artifacts built with `-Pautomodpack.autotest` in `merged/`

## Build

```bash
./gradlew build -Pautomodpack.autotest        # jars land in merged/
uv run --directory autotester autotester build-images
```

The autotest flag bundles the bridge and dev mixins. Release builds never contain them.
`build` runs the jar merge as its finalizer; `mergeJar` alone leaves stale modules.
Rebuild the client image after changing `images.client` or `headlessmc.ref`.

## Run

```bash
uv run --directory autotester autotester doctor       # JDKs, Docker, artifacts
uv run --directory autotester autotester plan --target all
uv run --directory autotester autotester run --target 26.2-fabric --scenario download-only
uv run --directory autotester autotester run --target all --scenario all
```

`--target` takes one id, a comma-separated list, or `all`. Repeating the flag keeps
only the last value. `plan` prints the cases and the skip reason for each one, without
starting anything.

| Flag | Meaning |
|---|---|
| `--scenario ID` | Scenario file in `scenarios/`. |
| `--jobs N` | Parallel targets. A native farm caps it at `farm.slots`. |
| `--connection-path MODE` | On a scenario with a connection-path matrix, run one opening. |
| `--provide native\|docker` | Spawn strategy. Default comes from `settings.yaml`. |
| `--artifact-dir`, `--out-dir` | Override `merged/` and `autotester/out/`. |
| `--netem`, `--loss`, `--server-netem` | Shape traffic with `tc`. Needs `--provide docker` and bridge networking. |

### Where cases run

`farm.provide` decides how a case spawns its processes.

- `native` runs on this kernel. The server is a `java` child. On Windows the client is
  a HeadlessMC process; on Linux it is a container on the host network. The kernel
  under test is the local one, so testing Windows means running the tester on Windows.
- `docker` runs itzg and HeadlessMC containers. Linux only. CI uses this.

On Windows, set `windows.headlessmc.path` (default `C:\amp\hmc.exe`) and a JDK home
per target Java version in `windows.javaHomes`.

A case owns a directory, three loopback ports, and a process tree. Ports are held
until the server binds them. The first native case for a target seeds
`autotester/.server-cache/<target>` with an itzg container; later cases copy it, which
is why Docker is still needed to seed.

A native farm never runs more cases at once than `farm.slots`; container cases answer only to
`--jobs`. `farm.mode: box` forces one slot.

## Scenarios

`all` is the release gate: secure bootstrap, group review, pack switching, a published
generation change with patch notes, server rollback, and object collection, run over
every connection path. The focused scenarios in `scenarios/` each diagnose one area.

A scenario is a `flow` of steps. Each step is a verb plus arguments, so most behaviour
needs no Python.

```yaml
id: download-only
flow:
  - use: boot              # macro from scenarios/_lib.yaml
  - use: accept_certificate
  - use: download_modpack
  - do: verify_files
  - do: quit
serverFiles:
  marker: config/amp-autotest-marker.json
  files:
    - path: config/example.txt
      content: "hello\n"
```

Step keys: `name` (log label), `when` (run only while a condition holds), `repeat`
(at most N times), `optional` (log a failure and continue).

Header keys that change how a scenario runs:

| Key | Meaning |
|---|---|
| `connectionPaths` | Matrix of `MAGIC`, `HOLEPUNCH`, `HTTP` openings. A path gives `bindPort`; an external host also gives `advertisedEndpointHost` and `endpointPort`. `deferFirstSyncToLogin` skips the launch-time preload so the first sync happens at login. |
| `platforms` | Kernels the scenario applies to. Omit to run on every kernel. |
| `requires` | Harness capabilities: `container_network`, `netem`, `rendered_ui`, `junction_probe`, `native_client`, `native_server`. |
| `mode` | `full` or `client-only`. |
| `network` | `bridge` or `host` transport for containers. |
| `targets`, `loaders`, `minecraft` | Scope the scenario to targets. |
| `renderClient` | Render the real UI for `screenshot` steps. |
| `topology`, `serverFiles` | Server config and the hosted pack. |

`client-only` stages a generation locally and boots just the client. Use it to iterate
on loader behaviour in seconds; see `scenarios/client-loads-offline.yaml`.

### Verbs, selectors, conditions

`autotester verbs` prints every verb and condition key with a one-line description.
`autotester validate --scenario <id>` expands macros and catches typos before Docker
starts. `run` performs the same check first.

UI steps take a selector:

```yaml
select:
  role: button              # button | textfield | any
  key: automodpack.cancel   # translation key; survives copy edits
  text: Verify              # exact match first, else case-insensitive substring
  enabled: true
  index: -1
```

Conditions are ANDed. Keys: `screen`, `screen_not`, `screen_none`, `element`,
`no_element`, `file`, `file_gone`, `log`, and `all`/`any`/`not`. The `log` condition
is the one worth knowing:

| Key | Meaning |
|---|---|
| `container`, `file` | Source: `server` or `client` stdout, or a file under the game dir. |
| `matches`, `matches_all`, `matches_any`, `not_matches` | Regexes that must or must not appear. |
| `count`, `min_count`, `max_count` | Quantify `matches`. |
| `tail` | Limit to the last N lines. The default scans the whole log. |
| `capture` | Store a regex group in a variable. |

`assert` and `wait_for` take the same condition under `that:` and `until:`.
Strings expand `${...}`: `${target.id}`, `${server.host}`, `${modpack}`, `${marker}`,
and anything captured.

Shared step sequences live in `scenarios/_lib.yaml` and are used as `- use: boot`.
Files starting with `_` are libraries, never scenarios.

### Tests for the engine

```bash
uv run --directory autotester --group dev pytest
```

## Output

Results go to `autotester/out/`. `results.json` records `ok`, each case, and each step
with its verb, duration, and error. A failed case keeps the steps up to the failing one.
The case directory holds `amp-*-s-*.log`, `amp-*-c-*.log`, the client game dir, and any
screenshots.

## Two sessions at once

Use one worktree per session. The server cache is locked per target, so two runs of the
same target wait instead of racing. `autotester clean` removes this checkout's output
and reaps resources of dead runs; it leaves Gradle daemons alone unless you pass
`--stop-daemons`.

## CI

`.github/workflows/ingame-tests.yml` is manual. Each job builds its own instrumented
targets and passes `--provide docker`. The workflow keeps per-target logs and a summary;
instrumented jars are not published as artifacts.

## Bridge

Jars built with `-Pautomodpack.autotest` carry a small file bridge under
`<gameDir>/automodpack/autotest/`. It answers `ping`, `gui`, `click`, `text`, `connect`,
`disconnect`, and `quit`; scenarios build behaviour from those.
