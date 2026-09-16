"""Checks that every shipped client locale is complete and format-safe."""
from __future__ import annotations

import json
from pathlib import Path


def test_client_locales_match_english_keys_and_placeholders():
	root = Path(__file__).parents[2] / "src/main/resources/assets/automodpack/lang"
	english = json.loads((root / "en_us.json").read_text(encoding="utf-8"))
	for locale in sorted(root.glob("*.json")):
		messages = json.loads(locale.read_text(encoding="utf-8"))
		assert set(messages) == set(english), locale.name
		assert all(isinstance(value, str) for value in messages.values()), locale.name
		for key, value in english.items():
			assert messages[key].count("%s") == value.count("%s"), f"{locale.name}: {key}"
