"""Minimal reader for canonical hconf files (the subset automodpack writes):
`key: value` members, `#` comments, nested braced blocks, inline arrays,
quoted strings and bare scalars. Sufficient for config assertions; not a parser."""

def _strip_comment(line):
    out = []
    in_quotes = False
    escape = False
    for char in line:
        if in_quotes:
            out.append(char)
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == '"':
                in_quotes = False
            continue
        if char == '"':
            in_quotes = True
            out.append(char)
        elif char == "#":
            break
        else:
            out.append(char)
    return "".join(out)

def _unescape(text):
    out = []
    escape = False
    for char in text:
        if escape:
            out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}.get(char, char))
            escape = False
        elif char == "\\":
            escape = True
        else:
            out.append(char)
    return "".join(out)

def _scalar(text):
    text = text.strip()
    if text.startswith('"') and text.endswith('"') and len(text) >= 2:
        return _unescape(text[1:-1])
    if text == "true":
        return True
    if text == "false":
        return False
    if text == "null":
        return None
    try:
        return int(text)
    except ValueError:
        pass
    try:
        return float(text)
    except ValueError:
        return text

def _split_inline(text):
    parts = []
    current = []
    in_quotes = False
    depth = 0
    for char in text:
        if in_quotes:
            current.append(char)
            if char == '"':
                in_quotes = False
            continue
        if char == '"':
            in_quotes = True
            current.append(char)
        elif char in "[{":
            depth += 1
            current.append(char)
        elif char in "]}":
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    parts.append("".join(current))
    return [part for part in (part.strip() for part in parts) if part]

def _value(text):
    text = text.strip()
    if text.startswith("[") and text.endswith("]"):
        return [_scalar(item) for item in _split_inline(text[1:-1])]
    return _scalar(text)

def read_hconf(path):
    """Reads one canonical hconf file into nested dicts/lists of scalars."""
    members = {}
    stack = [members]
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = _strip_comment(raw).strip()
        if not line or line == "{":
            continue
        if line == "}":
            if len(stack) > 1:
                stack.pop()
            continue
        key, separator, value = line.partition(":")
        if not separator:
            raise ValueError(f"malformed hconf member: {raw!r}")
        key = _scalar(key.strip())
        value = value.strip()
        if value == "{":
            new = {}
            stack[-1][key] = new
            stack.append(new)
        else:
            stack[-1][key] = _value(value)
    return members
