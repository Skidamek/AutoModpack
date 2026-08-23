"""Checks that every shipped client locale is complete and format-safe."""
from __future__ import annotations

import json
import re
from pathlib import Path

PLURAL_CATEGORY = re.compile(r"\.(one|few|many|other)$")


def _family(key: str) -> tuple[str, str | None]:
    match = PLURAL_CATEGORY.search(key)
    if match is None:
        return key, None
    return key[: match.start()], match.group(1)


def test_client_locales_match_english_keys_and_placeholders():
    root = Path(__file__).parents[2] / "src/main/resources/assets/automodpack/lang"
    english = json.loads((root / "en_us.json").read_text(encoding="utf-8"))
    for locale in sorted(root.glob("*.json")):
        messages = json.loads(locale.read_text(encoding="utf-8"))
        assert all(isinstance(value, str) for value in messages.values()), locale.name
        # Base keys must match exactly; CLDR plural categories may extend en_us with one/few/many.
        assert {_family(key)[0] for key in messages} == {_family(key)[0] for key in english}, locale.name
        for key, value in english.items():
            if key in messages:
                assert messages[key].count("%s") == value.count("%s"), f"{locale.name}: {key}"
        for key, value in messages.items():
            base, category = _family(key)
            if category is None:
                continue
            reference = next((english[k] for k in english if _family(k) == (base, "other")), None)
            if reference is None:
                reference = next(english[k] for k in english if _family(k)[0] == base)
            assert value.count("%s") == reference.count("%s"), f"{locale.name}: {key}"
        # Every plural family ships the universal ".other" fallback the plural helper falls back to.
        families = {}
        for key in messages:
            base, category = _family(key)
            if category is not None:
                families.setdefault(base, set()).add(category)
        english_families = {_family(key)[0] for key in english if _family(key)[1] is not None}
        for base in english_families:
            assert "other" in families.get(base, set()), f"{locale.name}: {base} misses the .other fallback"
