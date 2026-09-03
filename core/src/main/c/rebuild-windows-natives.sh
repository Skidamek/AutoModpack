#!/usr/bin/env bash
# Rebuilds the committed Windows native from source, reproducibly.
#
# The result must be byte-identical on every rebuild: the build flags fix the PE
# timestamp and image base, and the output name must stay win_file_stat.dll
# because mingw embeds it as the DLL's internal export name. CI (checkWinNatives)
# rebuilds and compares against the committed binary, so any drift between these
# sources and the shipped DLL fails loudly instead of degrading the accelerator
# silently on Windows.
#
# Requires: x86_64-w64-mingw32-gcc (mingw-w64) on PATH and JAVA_HOME pointing at
# a JDK (any version; only jni.h is used).
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
dll="$repo/core/src/main/resources/natives/windows-x86_64/win_file_stat.dll"

command -v x86_64-w64-mingw32-gcc >/dev/null || { echo "error: x86_64-w64-mingw32-gcc not on PATH (install mingw-w64)" >&2; exit 1; }
: "${JAVA_HOME:?error: JAVA_HOME must point at a JDK (jni.h is required)}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
x86_64-w64-mingw32-gcc -c -O0 -fno-PIC -fno-ident -o "$work/pe_reloc.o" "$repo/core/src/main/c/pe_reloc.c"
x86_64-w64-mingw32-gcc -shared -s -Os -fno-ident -fPIC -fno-stack-protector -fno-asynchronous-unwind-tables -fno-unwind-tables -nostdlib -Wl,--gc-sections -Wl,--enable-reloc-section -Wl,--dynamicbase -Wl,--high-entropy-va -Wl,--no-insert-timestamp -Wl,--image-base,0x6BCB5F440000 -Wl,-e,DllMain -Wl,-u,win_file_stat_keep -I "$repo/core/src/main/c" -I "$JAVA_HOME/include" -o "$dll" "$repo/core/src/main/c/windows_file_stat.c" "$work/pe_reloc.o" -lkernel32
sha256sum "$dll"
