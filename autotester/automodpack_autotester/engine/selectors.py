"""Match GUI elements from a bridge snapshot by a declarative selector.

A selector is a mapping; all given fields must match (AND):

  role     button | textfield | any      (default: any)
  text     exact (case-insensitive, trimmed) preferred, else substring
  text_any list of texts, any may match
  class    substring of the element's class name
  enabled  true/false
  visible  true/false
  index    pick the Nth match (default 0; negative counts from the end)
"""
from __future__ import annotations


def _elements(gui: dict, role: str) -> list:
    role = role.lower()
    if role in ("button", "buttons"):
        return list(gui.get("buttons", []))
    if role in ("textfield", "textfields", "field"):
        return list(gui.get("textFields", []))
    return list(gui.get("buttons", [])) + list(gui.get("textFields", []))


def _needles(selector: dict) -> list[str] | None:
    if selector.get("text") is not None:
        return [str(selector["text"])]
    if selector.get("text_any") is not None:
        return [str(t) for t in selector["text_any"]]
    return None


def _exact(element: dict, needles: list[str]) -> bool:
    text = str(element.get("text", "")).strip().lower()
    return any(text == n.strip().lower() for n in needles)


def _matches_text(element: dict, needles: list[str]) -> bool:
    if _exact(element, needles):
        return True
    text = str(element.get("text", "")).lower()
    return any(n.strip().lower() in text for n in needles)


def find_all(gui: dict, selector: dict) -> list:
    role = str(selector.get("role", "any"))
    needles = _needles(selector)
    klass = selector.get("class")
    enabled = selector.get("enabled")
    visible = selector.get("visible")
    out = []
    for e in _elements(gui, role):
        if enabled is not None and bool(e.get("enabled", False)) != bool(enabled):
            continue
        if visible is not None and bool(e.get("visible", False)) != bool(visible):
            continue
        if klass is not None and str(klass).lower() not in str(e.get("class", "")).lower():
            continue
        if needles is not None and not _matches_text(e, needles):
            continue
        out.append(e)
    return out


def find_one(gui: dict, selector: dict):
    matches = find_all(gui, selector)
    if not matches:
        return None
    if "index" in selector:
        idx = int(selector["index"])
        if idx < 0:
            idx += len(matches)
        return matches[idx] if 0 <= idx < len(matches) else None
    needles = _needles(selector)
    if needles:
        for e in matches:  # prefer an exact text match over a substring one
            if _exact(e, needles):
                return e
    return matches[0]
