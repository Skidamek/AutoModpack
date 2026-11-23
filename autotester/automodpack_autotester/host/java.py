"""Resolve a JDK binary for a target's major version."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path


def java_major(binary: str) -> int | None:
    try:
        result = subprocess.run([binary, "-version"], capture_output=True, text=True, timeout=15)
    except OSError:
        return None
    text = (result.stderr or result.stdout or "").splitlines()
    if not text:
        return None
    # openjdk version "21.0.5"  / java version "1.8.0_412"
    for line in text:
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
    """The java executable for ``version``. Mismatch against PATH java is a failure."""
    homes = dict(settings.get("javaHomes") or {})
    homes.update((settings.get("windows") or {}).get("javaHomes") or {})
    configured = homes.get(str(version)) or os.environ.get(f"JAVA_{version}_HOME") or os.environ.get(f"JAVA{version}_HOME")
    candidates: list[Path] = []
    if configured:
        candidates.append(Path(str(configured)))
    if sys.platform.startswith("win"):
        candidates.append(Path(rf"C:\amp\jdk{version}"))
        candidates.extend(sorted(Path(r"C:\Program Files\Eclipse Adoptium").glob(f"jdk-{version}*")))
        gradle_jdks = Path(os.environ.get("USERPROFILE", r"C:\Users\tester")) / ".gradle" / "jdks"
        candidates.extend(sorted(gradle_jdks.glob(f"*{version}*")))
    else:
        jvm = Path("/usr/lib/jvm")
        if jvm.is_dir():
            candidates.extend(sorted(jvm.glob(f"*{version}*")))
    for home in candidates:
        if home.is_file() and home.name in {"java", "java.exe"}:
            return str(home)
        for name in ("java", "java.exe"):
            exe = home / "bin" / name
            if exe.is_file():
                return str(exe)
    found = shutil.which("java")
    if found:
        major = java_major(found)
        if major == version:
            return found
        raise RuntimeError(f"java on PATH is {major}, target needs {version}; set javaHomes.{version}")
    raise RuntimeError(f"no Java {version} home found; set javaHomes.{version} or JAVA_{version}_HOME")


def java_home(settings: dict, version: int) -> Path:
    binary = Path(java_bin(settings, version))
    return binary.parent.parent
