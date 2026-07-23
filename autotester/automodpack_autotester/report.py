from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


def headline(error: str | None) -> str:
    text = (error or "").split("\n--- logs ---", 1)[0].strip()
    line = text.splitlines()[0] if text else ""
    return line or "unknown error"


def markdown_cell(text: str | None, limit: int = 180) -> str:
    value = " ".join((text or "").split()).replace("|", "\\|")
    return value[: limit - 1] + "…" if len(value) > limit else value


def github_escape(message: str | None) -> str:
    return (message or "").replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def github_property_escape(value: str | None) -> str:
    return github_escape(value).replace(",", "%2C").replace(":", "%3A")


def _artifact_target(path: Path) -> str:
    for parent in path.parents:
        if parent.name.startswith("ingame-results-"):
            return parent.name.removeprefix("ingame-results-")
    return "unknown"


def aggregate_results(results_dir: Path, expected_matrix: str) -> dict:
    results: list[dict] = []
    for path in sorted(results_dir.glob("**/results.json")):
        try:
            data = json.loads(path.read_text())
            results.extend(data.get("results", []))
        except (OSError, json.JSONDecodeError, AttributeError, TypeError):
            results.append(
                {
                    "target": _artifact_target(path),
                    "ok": False,
                    "error": "results.json missing or invalid",
                }
            )

    try:
        expected = json.loads(expected_matrix).get("target", [])
        if not isinstance(expected, list):
            raise TypeError
        matrix_error = "target preparation selected no test targets" if not expected else None
    except (json.JSONDecodeError, AttributeError, TypeError):
        expected = []
        matrix_error = "target preparation produced no valid test matrix"

    if matrix_error:
        results.append({"target": "workflow", "ok": False, "error": matrix_error})

    found = {str(result.get("target")) for result in results}
    for target in expected:
        if target not in found:
            results.append(
                {
                    "target": target,
                    "ok": False,
                    "error": "test job produced no results (build, setup, or runner failed)",
                }
            )

    passed = sum(1 for result in results if result.get("ok"))
    total = len(results)
    return {
        "ok": passed == total,
        "total": total,
        "passed": passed,
        "failed": total - passed,
        "results": results,
    }


def render_markdown(summary: dict) -> str:
    results = summary["results"]
    failures = [result for result in results if not result.get("ok")]
    lines = [
        "## In-game Test Results",
        "",
        f"**{'PASS' if summary['ok'] else 'FAIL'}** — {summary['passed']}/{summary['total']} passed, {summary['failed']} failed",
        "",
        "| Status | Target | Duration | Error |",
        "|--------|--------|----------|-------|",
    ]
    for result in results:
        status = "✅" if result.get("ok") else "❌"
        duration = f"{result.get('duration', 0):.1f}s" if "duration" in result else "-"
        error = markdown_cell(headline(result.get("error"))) if not result.get("ok") else ""
        lines.append(f"| {status} | {result['target']} | {duration} | {error} |")

    if failures:
        lines.extend(["", "### Failure details"])
        for result in failures:
            error = (result.get("error") or "unknown error").replace("```", "ʼʼʼ").strip()
            lines.extend(
                [
                    "",
                    f"<details><summary>{result['target']}</summary>",
                    "",
                    "```",
                    error,
                    "```",
                    "",
                    "</details>",
                ]
            )

    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Aggregate in-game autotester results")
    parser.add_argument("--results-dir", type=Path, default=Path("."))
    parser.add_argument("--expected-matrix", default=os.environ.get("EXPECTED_MATRIX", ""))
    parser.add_argument("--json-output", type=Path, default=Path("aggregated.json"))
    parser.add_argument("--markdown-output", type=Path, default=Path("summary.md"))
    parser.add_argument("--github-output", type=Path, default=os.environ.get("GITHUB_OUTPUT"))
    args = parser.parse_args(argv)

    summary = aggregate_results(args.results_dir, args.expected_matrix)
    args.json_output.write_text(json.dumps(summary, indent=2) + "\n")
    args.markdown_output.write_text(render_markdown(summary))

    for result in summary["results"]:
        if result.get("ok"):
            continue
        error = result.get("error", "unknown error")
        print(f"::error title={github_property_escape(headline(error))}::{github_escape(error)}")

    if args.github_output:
        with args.github_output.open("a") as output:
            print(f"ok={str(summary['ok']).lower()}", file=output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
