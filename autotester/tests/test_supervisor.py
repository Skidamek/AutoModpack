from __future__ import annotations

from types import SimpleNamespace

from automodpack_autotester.supervisor import SCOPE_LABEL, cleanup_scope, resource_labels


class _Resources:
    def __init__(self, resources):
        self.resources = resources
        self.filters = None

    def list(self, **kwargs):
        self.filters = kwargs.get("filters")
        return self.resources


def test_resource_labels_use_the_explicit_run_scope():
    assert resource_labels("deadbeef") == {SCOPE_LABEL: "deadbeef"}


def test_cleanup_scope_removes_only_label_selected_resources():
    container = SimpleNamespace(remove=lambda **kwargs: removed.append(("container", kwargs)))
    network = SimpleNamespace(remove=lambda: removed.append(("network", {})))
    removed = []
    client = SimpleNamespace(containers=_Resources([container]), networks=_Resources([network]))

    cleanup_scope("deadbeef", client)

    expected_filter = {"label": f"{SCOPE_LABEL}=deadbeef"}
    assert client.containers.filters == expected_filter
    assert client.networks.filters == expected_filter
    assert removed == [("container", {"force": True}), ("network", {})]
