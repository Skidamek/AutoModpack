from __future__ import annotations

import json

from automodpack_autotester.report import (
    aggregate_results,
    github_escape,
    github_property_escape,
    render_markdown,
)


def test_aggregation_reports_invalid_and_missing_targets(tmp_path):
    passed = tmp_path / "ingame-results-a"
    passed.mkdir()
    (passed / "results.json").write_text(
        json.dumps({"results": [{"target": "a", "ok": True, "duration": 1.5}]})
    )
    invalid = tmp_path / "ingame-results-b"
    invalid.mkdir()
    (invalid / "results.json").write_text("not json")

    summary = aggregate_results(tmp_path, json.dumps({"target": ["a", "b", "c"]}))

    assert summary["ok"] is False
    assert (summary["total"], summary["passed"], summary["failed"]) == (3, 1, 2)
    failures = {result["target"]: result["error"] for result in summary["results"] if not result["ok"]}
    assert failures == {
        "b": "results.json missing or invalid",
        "c": "test job produced no results (build, setup, or runner failed)",
    }

    invalid_matrix = aggregate_results(tmp_path, '{"target": null}')
    assert any(result["target"] == "workflow" for result in invalid_matrix["results"])


def test_report_output_escapes_workflow_commands_and_markdown():
    error = "bad %, title: | value\nsecond line\n```escape```"
    summary = {
        "ok": False,
        "total": 1,
        "passed": 0,
        "failed": 1,
        "results": [{"target": "test", "ok": False, "error": error}],
    }

    assert github_escape(error) == "bad %25, title: | value%0Asecond line%0A```escape```"
    assert github_property_escape("bad %, title:") == "bad %25%2C title%3A"
    markdown = render_markdown(summary)
    assert "bad %, title: \\| value" in markdown
    assert "ʼʼʼescapeʼʼʼ" in markdown
    assert "```escape```" not in markdown
