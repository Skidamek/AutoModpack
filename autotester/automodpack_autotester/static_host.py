"""The barebones static HTTPS host: one container serving an exported modpack tree.

The static-host scenario serves the pack from ``http.server`` wrapped in TLS
instead of the mod's own listener, so the client must complete a full sync on a
host with no ranges, no conditionals, and no keep-alive. The server container
keeps running for the login/advertisement only; this container holds the files.
"""
from __future__ import annotations

import re

from .docker_harness import _container_logs, _remove_container, _run_container, _wait_for_log
from .engine import Context
from .engine.registry import verb
from .supervisor import resource_labels

# The fixed DNS alias the scenario advertises as the modpack endpoint; the
# container's own name is random per case, the alias is stable.
STATIC_HOST_ALIAS = "automodpack-static-host"


@verb("start_static_host")
def _v_start_static_host(ctx: Context, step):
    """Serve an exported contract tree (head/journal/objects) over barebones HTTPS.

    Captures the container's self-signed certificate fingerprint into the
    ``fingerprint`` var so ``accept_certificate`` can answer the first-contact
    prompt with the endpoint's own certificate.
    """
    rel = str(step.get("dir", "")).strip()
    if not rel:
        raise ValueError("start_static_host needs the exported tree's dir relative to the server game dir")
    host_dir = ctx.server_dir / rel
    missing = [name for name in ("head", "journal") if not (host_dir / name).is_file()]
    if missing:
        raise RuntimeError(f"exported tree {host_dir} is missing {missing}; wait for the server's export receipt first")
    port = int(step.get("port", 8443))

    _remove_container(ctx.static_name)
    _run_container(
        name=ctx.static_name,
        image=ctx.static_host_image,
        network=ctx.net_name,
        env={},
        mounts=[(host_dir, "/srv/export", True)],
        command=["/srv/export", str(port)],
        aliases=[STATIC_HOST_ALIAS],
        labels=resource_labels(ctx.resource_scope),
    )
    _wait_for_log(ctx.static_name, "certificate fingerprint:", timeout=60)
    match = re.search(r"certificate fingerprint:\s*([0-9A-Fa-f:]+)", _container_logs(ctx.static_name))
    if match is None:
        raise RuntimeError(f"static host did not report a certificate fingerprint: {_container_logs(ctx.static_name)[-500:]}")
    ctx.vars["fingerprint"] = match.group(1)
    ctx.vars["static_host_endpoint"] = f"{STATIC_HOST_ALIAS}:{port}"
