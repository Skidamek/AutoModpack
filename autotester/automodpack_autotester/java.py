"""Cross-platform JDK discovery and fast-startup JVM flags."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from functools import lru_cache
from pathlib import Path


@lru_cache(maxsize=32)
def java_major(binary: str) -> int | None:
    try:
        result = subprocess.run([binary, "-version"], capture_output=True, text=True, timeout=15)
    except OSError:
        return None
    for line in (result.stderr or result.stdout or "").splitlines():
        if "version" not in line:
            continue
        start = line.find('"')
        end = line.find('"', start + 1)
        if start < 0 or end < 0:
            continue
        token = line[start + 1 : end]
        if token.startswith("1."):
            return int(token.split(".")[1])
        return int(token.split(".")[0])
    return None


def java_bin(settings: dict, version: int) -> str:
    """Resolve the java executable for ``version``; raises if none matches."""
    homes = dict(settings.get("javaHomes") or {})
    homes.update((settings.get("windows") or {}).get("javaHomes") or {})
    configured = homes.get(str(version)) or os.environ.get(f"JAVA_{version}_HOME") or os.environ.get(f"JAVA{version}_HOME")
    candidates: list[Path] = []
    if configured:
        candidates.append(Path(str(configured)).expanduser())
    if sys.platform.startswith("win"):
        candidates.append(Path(rf"C:\amp\jdk{version}"))
        candidates.extend(sorted(Path(r"C:\Program Files\Eclipse Adoptium").glob(f"jdk-{version}*")))
        gradle_jdks = Path(os.environ.get("USERPROFILE", str(Path.home()))) / ".gradle" / "jdks"
        if gradle_jdks.is_dir():
            candidates.extend(sorted(gradle_jdks.glob(f"*{version}*")))
    elif sys.platform == "darwin":
        jvms = Path("/Library/Java/JavaVirtualMachines")
        if jvms.is_dir():
            for d in sorted(jvms.glob(f"*{version}*")):
                candidates.append(d / "Contents" / "Home")
    else:
        for base in (Path("/usr/lib/jvm"), Path.home() / ".gradle" / "jdks", Path.home() / ".sdkman" / "candidates" / "java"):
            if base.is_dir():
                candidates.extend(sorted(base.glob(f"*{version}*")))
    for home in candidates:
        if home.is_file() and home.name in {"java", "java.exe"}:
            return str(home)
        for name in ("java", "java.exe"):
            exe = home / "bin" / name
            if exe.is_file() and java_major(str(exe)) == version:
                return str(exe)
    found = shutil.which("java")
    if found and java_major(found) == version:
        return found
    raise RuntimeError(f"No Java {version} found; install JDK {version} or set JAVA_{version}_HOME")


def java_home(settings: dict, version: int) -> Path:
    return Path(java_bin(settings, version)).parent.parent


def fast_jvm_flags(settings: dict, memory: str) -> list[str]:
    """Low-overhead JVM flags tuned for short-lived test processes (no GC override; MC version.json sets its own)."""
    flags = ["-Xms256M", f"-Xmx{memory}"]
    if bool((settings.get("jvm") or {}).get("fastStartup", True)):
        flags.append("-XX:TieredStopAtLevel=1")
    return flags
