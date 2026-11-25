# AutoModpack Autotester v2

Fast, native-first in-game integration tests for AutoModpack.

- **Zero Docker required** for local Linux, macOS, and Windows runs: downloads Fabric/Forge/NeoForge server jars and the native HeadlessMC 3 binary once into `~/.cache/automodpack-autotest/`, hardlinks immutable jars/libraries into each case, and caches a clean flat world per target.
- **Fast TCP JSON-RPC bridge:** `AutoTestBridge` listens on loopback TCP with request IDs (<1ms round-trip), normalized screen names, keyboard/scroll input, layout bounds checks, and autotest-only fast timers (`-Dautomodpack.autotest.fastTimers=true`).
- **Typed Python scenarios:** decoupled transport matrix (`transport-holepunch`, `transport-magic`, `transport-http`), single-pass release gate (`gate`), fast client-only smoke tests (`offline-smoke`, `offline-mod-fixture`), programmable in-process `FaultProxy` (`fault-mid-transfer-drop`), `SIGKILL` crash recovery (`fault-sigkill-recovery`), and `multi-client` concurrency.
- **Real Windows NTFS testing from Linux:** KVM/QEMU live-snapshot worker (`--provider windows-vm`) restores a booted Windows guest in seconds with an ephemeral copy-on-write disk and Win32 exclusive file-lock / junction / long-path primitives.

## Quick Start

```bash
./gradlew build -Pautomodpack.autotest
uv run --directory autotester autotester doctor --target 26.2-fabric
uv run --directory autotester autotester list
uv run --directory autotester autotester run --target 26.2-fabric --scenario offline-smoke
uv run --directory autotester autotester run --target 26.2-fabric --scenario transport,gate --jobs 3
```

## Windows VM Worker (on Linux with `/dev/kvm`)

```bash
uv run --directory autotester autotester windows-vm status
uv run --directory autotester autotester windows-vm init --iso /path/to/windows.iso --virtio-iso /path/to/virtio-win.iso
# Inside Windows: install JDK 17/21/25, C:\amp\hmc.exe, and autostart windows_agent.py
uv run --directory autotester autotester windows-vm snapshot
uv run --directory autotester autotester run --provider windows-vm --target 26.3-neoforge --scenario all --jobs 2
```

If a Windows KVM guest running `windows_agent.py` is already listening on `127.0.0.1:19450` (or `AUTOMODPACK_WINDOWS_AGENT=host:port`), `--provider windows-vm` reuses it directly without rebooting the VM.

## Unit Tests

```bash
uv run --directory autotester --group dev pytest
```
