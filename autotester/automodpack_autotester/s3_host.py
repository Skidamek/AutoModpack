"""The S3-compatible host: one MinIO container serving the exported tree from a bucket.

The s3-host scenario stands up the shape an operator would run in production:
MinIO with a self-signed TLS certificate, a public-download bucket holding the
exported contract tree, and the client pointed at the bucket endpoint. MinIO
serves virtual-host style when the Host header names the bucket, so the
container's DNS alias doubles as the bucket name and the client's fixed routes
(/head, /journal, /objects/<sha1>) land inside the bucket untouched.
"""
from __future__ import annotations

import hashlib
import subprocess

from .docker_harness import _container, _container_logs, _remove_container, _run_container, _wait_exited, _wait_for_log
from .engine import Context
from .engine.registry import verb
from .supervisor import resource_labels

# MinIO serves virtual-host style when the Host header reads <bucket>.<domain>:
# the container answers to the dotted alias pack.s3, the bucket is named pack,
# and the client's fixed routes (/head, /journal, /objects/<sha1>) land inside
# the bucket untouched - the exact shape of a real bucket endpoint.
BUCKET = "pack"
S3_DOMAIN = "s3"
HOST_ALIAS = f"{BUCKET}.{S3_DOMAIN}"
ENDPOINT_PORT = 9000


@verb("start_s3_host")
def _v_start_s3_host(ctx: Context, step):
    """Serve the exported contract tree from a MinIO bucket over TLS.

    Captures the certificate fingerprint into the ``fingerprint`` var so
    ``accept_certificate`` can answer the first-contact prompt with the
    endpoint's own certificate.
    """
    rel = str(step.get("dir", "")).strip()
    if not rel:
        raise ValueError("start_s3_host needs the exported tree's dir relative to the server game dir")
    host_dir = ctx.server_dir / rel
    missing = [name for name in ("head", "journal") if not (host_dir / name).is_file()]
    if missing:
        raise RuntimeError(f"exported tree {host_dir} is missing {missing}; wait for the server's export receipt first")

    # The certificate is generated at case setup, host-side (openssl is a harness
    # dependency the same way docker is); MinIO mounts it read-only.
    work = ctx.out_dir / "s3-host"
    work.mkdir(parents=True, exist_ok=True)
    cert, key = work / "public.crt", work / "private.key"
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key), "-out", str(cert),
         "-days", "2", "-subj", f"/CN={HOST_ALIAS}", "-addext", f"subjectAltName=DNS:{HOST_ALIAS}"],
        check=True, capture_output=True,
    )
    der = subprocess.run(["openssl", "x509", "-in", str(cert), "-outform", "DER"], check=True, capture_output=True).stdout
    ctx.vars["fingerprint"] = hashlib.sha256(der).hexdigest()

    _remove_container(ctx.s3_name)
    mc_name = ctx.s3_name + "-mc"
    _remove_container(mc_name)
    _run_container(
        name=ctx.s3_name,
        image=ctx.minio_image,
        network=ctx.net_name,
        env={"MINIO_DOMAIN": S3_DOMAIN, "MINIO_ROOT_USER": "automodpack", "MINIO_ROOT_PASSWORD": "automodpack-autotest"},
        mounts=[(work, "/certs", True)],
        command=["server", "/data", "--address", f":{ENDPOINT_PORT}", "--console-address", ":9001", "--certs-dir", "/certs"],
        aliases=[HOST_ALIAS],
        labels=resource_labels(ctx.resource_scope),
    )
    _wait_for_log(ctx.s3_name, "MinIO Object Storage Server", timeout=120)

    # Publish through the MinIO client: create the bucket, mirror the exported
    # tree, then open it for anonymous download - the same recipe the hosting
    # docs give operators, where aws s3 sync / rclone stand in for mc.
    _run_container(
        name=mc_name,
        image=ctx.minio_client_image,
        network=ctx.net_name,
        env={},
        mounts=[(host_dir, "/srv/export", True)],
        entrypoint=["/bin/sh", "-c"],
        # --insecure is a per-command global flag: the self-signed cert is the
        # operator's own, exactly like the production recipe with a private CA.
        # The alias targets the container's plain name on purpose: MINIO_DOMAIN
        # makes vhost style authoritative for pack.s3, and mc speaks path style.
        command=[f"mc alias set local https://{ctx.s3_name}:{ENDPOINT_PORT} automodpack automodpack-autotest --insecure "
                 f"&& mc --insecure mb --ignore-existing local/{BUCKET} "
                 f"&& mc --insecure mirror --overwrite /srv/export local/{BUCKET} "
                 f"&& mc --insecure anonymous set download local/{BUCKET}"],
        labels=resource_labels(ctx.resource_scope),
    )
    _wait_exited(mc_name, timeout=300)
    exit_code = _container(mc_name).attrs.get("State", {}).get("ExitCode", -1)
    if exit_code != 0:
        raise RuntimeError(f"the mc upload failed (exit {exit_code}): {_container_logs(mc_name)[-800:]}")
    ctx.vars["static_host_endpoint"] = f"{HOST_ALIAS}:{ENDPOINT_PORT}"
