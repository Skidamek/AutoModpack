"""Checks that every shipped client locale is complete and format-safe.

Plural families are defined by UiFormat.plural() calls, not by guessing at
``.one``/``.other`` key suffixes: ``automodpack.browser.content.other`` is a
content kind named "other", while ``automodpack.browser.summary.other`` is a
CLDR fallback. Extra CLDR suffixes on a known family (pl ``.few``/``.many``)
are allowed so locales can grow without exact key-set parity.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

CLDR_SUFFIXES = ("zero", "one", "two", "few", "many", "other")
QUOTED_KEY = re.compile(r'"(automodpack\.[A-Za-z0-9_.]+)"')
PLURAL_CALL = re.compile(r'UiFormat\.plural\s*\((?:[^;]|\n)*?,\s*"(automodpack\.[A-Za-z0-9_.]+)"')
IGNORE_QUOTED_PREFIXES = ("automodpack.autotest", "automodpack.data.root")


def _repo() -> Path:
    return Path(__file__).parents[2]


def _lang_root() -> Path:
    return _repo() / "src/main/resources/assets/automodpack/lang"


def _english() -> dict[str, str]:
    return json.loads((_lang_root() / "en_us.json").read_text(encoding="utf-8"))


def _main_java_sources(repo: Path):
    for path in repo.rglob("*.java"):
        posix = path.as_posix()
        if "/src/main/java/" not in posix:
            continue
        if any(part in posix for part in ("/build/", "/bin/", "/versions/", "/autotester/")):
            continue
        yield path


def _plural_families(repo: Path) -> set[str]:
    families: set[str] = set()
    for source in _main_java_sources(repo):
        families.update(PLURAL_CALL.findall(source.read_text(encoding="utf-8", errors="replace")))
    return families


def _member(key: str, families: set[str]) -> tuple[str, str] | None:
    for suffix in CLDR_SUFFIXES:
        token = "." + suffix
        if key.endswith(token) and key[: -len(token)] in families:
            return key[: -len(token)], suffix
    return None


def _regular_keys(keys, families: set[str]) -> set[str]:
    return {key for key in keys if _member(key, families) is None}


def test_client_locales_match_english_keys_and_placeholders():
    repo = _repo()
    english = _english()
    families = _plural_families(repo)
    assert families, "UiFormat.plural() calls define the locale plural families"
    for base in families:
        assert f"{base}.other" in english, f"en_us.json misses the .other fallback for {base}"

    english_regular = _regular_keys(english, families)
    for locale in sorted(_lang_root().glob("*.json")):
        messages = json.loads(locale.read_text(encoding="utf-8"))
        assert all(isinstance(value, str) for value in messages.values()), locale.name
        assert _regular_keys(messages, families) == english_regular, locale.name
        for base in families:
            assert f"{base}.other" in messages, f"{locale.name}: {base} misses the .other fallback"
        for key, value in messages.items():
            member = _member(key, families)
            if member is None:
                continue
            reference = english.get(key) or english[f"{member[0]}.other"]
            assert value.count("%s") == reference.count("%s"), f"{locale.name}: {key}"
        for key, value in english.items():
            if key in messages:
                assert messages[key].count("%s") == value.count("%s"), f"{locale.name}: {key}"


def test_java_referenced_lang_keys_exist():
    """Every static lang key referenced from Java must resolve; missing keys render as raw text in-game."""
    repo = _repo()
    english = _english()
    keys = set(english)
    families = _plural_families(repo)
    referenced: dict[str, str] = {}
    for source in _main_java_sources(repo):
        text = source.read_text(encoding="utf-8", errors="replace")
        for key in QUOTED_KEY.findall(text):
            if key.startswith(IGNORE_QUOTED_PREFIXES):
                continue
            if key.endswith(".jar"):
                continue
            referenced.setdefault(key, str(source))
    missing = []
    for key, where in sorted(referenced.items()):
        if key in keys or key in families:
            continue
        if key.endswith(".") and any(existing.startswith(key) for existing in keys):
            continue
        if any(existing.startswith(key + ".") for existing in keys):
            continue
        missing.append(f"{key} ({where})")
    assert not missing, f"lang keys referenced from Java but absent from en_us.json: {missing}"
    for base in families:
        assert f"{base}.other" in keys, f"UiFormat.plural({base!r}) has no {base}.other in en_us.json"
