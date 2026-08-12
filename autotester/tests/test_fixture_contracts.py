"""Contracts for autotester fixtures, archives, and staged generations."""

from __future__ import annotations

import hashlib
import io
import json
import zipfile
from pathlib import Path

import pytest
from automodpack_autotester import runner
from automodpack_autotester.config import (
    load_scenarios,
    load_targets,
    parse_server_files,
)
from automodpack_autotester.engine.steps_io import seed_unowned_local_file, write_file
from automodpack_autotester.generation_identity import CanonicalEncoder
from automodpack_autotester.mod_fixtures import (
    assert_valid_mod_fixture,
    pack_metadata_for,
    valid_mod_jar_bytes,
)


def test_release_fixture_uses_server_config_and_declared_group_directories(make_ctx):
    scenario = load_scenarios()["all"]
    assert (
        scenario["topology"]["server"]["automodpack"]["config"]["validateSecrets"]
        is True
    )
    server_files = parse_server_files(scenario)
    ctx = make_ctx(
        scenario=scenario,
        modpack_name=server_files.modpack_name,
        marker_rel=server_files.marker,
        scenario_files=server_files.files,
    )
    ctx.artifact.write_bytes(b"autotest-artifact")
    runner._prepare_server(ctx)

    config_path = ctx.server_dir / "automodpack" / "server-config.json"
    assert config_path.is_file()
    assert not (ctx.server_dir / "automodpack" / "automodpack-server.json").exists()
    assert set(json.loads(config_path.read_text(encoding="utf-8"))["groups"]) == {
        "main",
        "visual",
        "addon",
        "alternative",
        "windows",
    }

    host_root = ctx.server_dir / "automodpack" / "host-modpack"
    assert (host_root / "main" / "config/amp-autotest-alpha.txt").is_file()
    assert (host_root / "visual" / "config/amp-autotest-visual.txt").is_file()
    assert (host_root / "addon" / "config/amp-autotest-addon.txt").is_file()
    assert (host_root / "alternative" / "config/amp-autotest-alternative.txt").is_file()
    assert (host_root / "windows" / "config/amp-autotest-windows.txt").is_file()
    assert_valid_mod_fixture(
        (host_root / "main" / "mods/amp-autotest-removed.jar").read_bytes(),
        {
            "modId": "amp_autotest_removed",
            "version": "1.0.0-published",
            "marker": "published",
        },
        ctx.target.minecraft,
    )
    assert not (host_root / "main" / "config/amp-autotest-visual.txt").exists()


def test_reset_client_generation_preserves_ordinary_mods(make_ctx):
    ctx = make_ctx()
    client = ctx.game_dir / "automodpack/client"
    (client / "records/old").mkdir(parents=True)
    (client / "records/old/manifest.json").write_text("{}", encoding="utf-8")
    (client / "active/config").mkdir(parents=True)
    (client / "active/config/old.txt").write_text("old", encoding="utf-8")
    (client / "baselines/packaaa").mkdir(parents=True)
    (client / "baselines/packaaa/baseline.json").write_text("{}", encoding="utf-8")
    (client / "overlays/packaaa/config").mkdir(parents=True)
    (client / "overlays/packaaa/config/local.txt").write_text("local", encoding="utf-8")
    (client / "data/objects").mkdir(parents=True)
    (client / "data/objects" / ("a" * 40)).write_bytes(b"cached")
    (client / "data/known-hosts.json").write_text('{"hosts": {}}', encoding="utf-8")
    (client / "data/packs/packaaa").mkdir(parents=True)
    (client / "data/packs/packaaa/connection.json").write_text('{"connection": {}}', encoding="utf-8")
    (client / "active-state.json").write_text("{}", encoding="utf-8")
    (ctx.game_dir / "automodpack/client-config.json").write_text(
        '{"selectedModpackId": "packaaa"}', encoding="utf-8"
    )
    fixture = {
        "modId": "amp_autotest_removed",
        "version": "1.0.0-published",
        "marker": "published",
    }
    (ctx.game_dir / "mods/old.jar").write_bytes(valid_mod_jar_bytes(fixture))
    runner._v_reset_client_generation(ctx, {})

    assert not (client / "records").exists()
    assert not (client / "active").exists()
    assert not (client / "baselines").exists()
    assert not (client / "overlays").exists()
    assert not (client / "data/objects").exists()
    assert not (client / "active-state.json").exists()
    assert_valid_mod_fixture((ctx.game_dir / "mods/old.jar").read_bytes(), fixture)
    assert (client / "data/known-hosts.json").read_text(encoding="utf-8") == '{"hosts": {}}'
    assert (
        client / "data/packs/packaaa/connection.json"
    ).read_text(encoding="utf-8") == '{"connection": {}}'
    assert (
        ctx.game_dir / "automodpack/client-config.json"
    ).read_text(encoding="utf-8") == '{"selectedModpackId": "packaaa"}'


def test_unowned_local_fixture_writes_a_valid_cross_loader_archive(make_ctx):
    fixture = {
        "modId": "amp_autotest_unowned",
        "version": "1.0.0-local-unowned",
        "marker": "unowned-local",
    }
    ctx = make_ctx()

    seed_unowned_local_file(ctx, {"path": "mods/local-unowned.jar", "fixture": fixture})

    assert_valid_mod_fixture(
        (ctx.game_dir / "mods/local-unowned.jar").read_bytes(),
        fixture,
        ctx.target.minecraft,
    )


def test_write_file_writes_local_edit(make_ctx):
    ctx = make_ctx()

    write_file(ctx, {"path": "config/editable.txt", "content": "local edit\n"})

    assert (ctx.game_dir / "config/editable.txt").read_text(
        encoding="utf-8"
    ) == "local edit\n"


def test_write_file_rejects_path_escape(make_ctx):
    ctx = make_ctx()

    with pytest.raises(ValueError, match="escapes the client game directory"):
        write_file(ctx, {"path": "../outside.txt", "content": "must not escape\n"})


def test_metadata_only_fixture_uses_no_code_loader_metadata():
    fixture = {
        "modId": "amp_autotest_metadata",
        "version": "1.0.0",
        "marker": "metadata",
    }

    with zipfile.ZipFile(io.BytesIO(valid_mod_jar_bytes(fixture))) as archive:
        forge = archive.read("META-INF/mods.toml").decode("utf-8")
        neoforge = archive.read("META-INF/neoforge.mods.toml").decode("utf-8")
        pack = json.loads(archive.read("pack.mcmeta"))
        names = archive.namelist()

    for metadata in (forge, neoforge):
        assert 'modLoader = "lowcodefml"' in metadata
        assert 'loaderVersion = "[1,)"' in metadata
        assert "amp_autotest_metadata" in metadata
    assert pack["pack"]["pack_format"] == 15
    assert not any(name.endswith(".class") for name in names)


@pytest.mark.parametrize(
    ("minecraft_version", "format_fields"),
    [
        ("1.18.2", {"pack_format": 8}),
        ("1.20.1", {"pack_format": 15}),
        ("1.21.8", {"pack_format": 64}),
        ("1.21.10", {"min_format": 69, "max_format": 69}),
    ],
)
def test_fixture_pack_metadata_matches_target_receipts(
    minecraft_version, format_fields
):
    fixture = {
        "modId": "amp_autotest_metadata",
        "version": "1.0.0",
        "marker": "metadata",
    }
    payload = valid_mod_jar_bytes(fixture, minecraft_version)

    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        pack = json.loads(archive.read("pack.mcmeta"))

    assert pack == pack_metadata_for(minecraft_version)
    assert pack["pack"] == {
        "description": "AutoModpack autotest fixture",
        **format_fields,
    }
    assert_valid_mod_fixture(payload, fixture, minecraft_version)


def test_fixture_pack_metadata_covers_configured_targets():
    for target in load_targets().values():
        pack_metadata_for(target.minecraft)


def test_staged_generation_uses_actual_file_metadata(make_ctx):
    ctx = make_ctx()
    root = ctx.game_dir / "staged"
    marker = root / ctx.marker_rel
    marker.parent.mkdir(parents=True)
    marker.write_text("marker\n", encoding="utf-8")
    mod = root / "mods" / "fixture.jar"
    mod.parent.mkdir()
    mod.write_bytes(b"fixture")

    data_root = root.parent / "data"
    generation = runner._write_staged_generation(ctx, root, "fixture7", data_root)

    manifest = json.loads(
        (
            root.parent / "records" / generation["generationId"] / "manifest.json"
        ).read_text(encoding="utf-8")
    )
    by_path = manifest["groups"]["main"]["files"]
    assert by_path["mods/fixture.jar"]["size"] == str(len(b"fixture"))
    assert by_path["mods/fixture.jar"]["sha1"] == hashlib.sha1(b"fixture").hexdigest()
    assert by_path["mods/fixture.jar"]["editable"] is False
    assert by_path["config/amp-autotest-marker.json"]["editable"] is True


def test_staged_generation_preserves_explicit_editable_file_metadata(make_ctx):
    ctx = make_ctx()
    root = ctx.game_dir / "staged"
    path = root / "config/editable.txt"
    path.parent.mkdir(parents=True)
    path.write_text("server default\n", encoding="utf-8")

    data_root = root.parent / "data"
    generation = runner._write_staged_generation(
        ctx, root, "fixture8", data_root, editable_paths={"config/editable.txt"}
    )

    manifest = json.loads(
        (
            root.parent / "records" / generation["generationId"] / "manifest.json"
        ).read_text(encoding="utf-8")
    )
    assert (
        manifest["groups"]["main"]["files"]["config/editable.txt"]["editable"] is True
    )


def test_record_only_staging_does_not_replace_active_state(make_ctx):
    ctx = make_ctx(
        modpack_name="Pack A",
        marker_rel=Path("config/marker.json"),
        scenario_files=[(Path("config/a.txt"), "a")],
    )
    active_state = ctx.game_dir / "automodpack/client/active-state.json"
    active_state.parent.mkdir(parents=True, exist_ok=True)
    active_state.write_text('{"modpackId":"packaaa"}', encoding="utf-8")

    runner._v_stage_modpack(
        ctx,
        {
            "recordOnly": True,
            "packId": "packbbb",
            "packName": "Pack B",
            "files": [{"path": "config/b.txt", "content": "b"}],
        },
    )

    assert json.loads(active_state.read_text(encoding="utf-8"))["modpackId"] == "packaaa"
    records = list(
        (ctx.game_dir / "automodpack/client/records").glob("*/manifest.json")
    )
    assert len(records) == 1
    assert json.loads(records[0].read_text(encoding="utf-8"))["modpackName"] == "Pack B"


def test_record_only_staging_links_same_pack_history(make_ctx):
    ctx = make_ctx(modpack_name="Pack B", marker_rel=Path("config/marker.json"))
    runner._v_stage_modpack(
        ctx,
        {
            "recordOnly": True,
            "packId": "packbbb",
            "packName": "Pack B",
            "patchNotes": "Pack B root.",
            "files": [{"path": "config/b.txt", "content": "b"}],
        },
    )
    runner._v_stage_modpack(
        ctx,
        {
            "recordOnly": True,
            "packId": "packbbb",
            "packName": "Pack B",
            "patchNotes": "Pack B update.",
            "files": [{"path": "config/b.txt", "content": "b2"}],
        },
    )

    records = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in (ctx.game_dir / "automodpack/client/records").glob("*/manifest.json")
    ]
    records.sort(key=lambda manifest: manifest["generation"]["createdAt"])
    assert records[1]["generation"]["parentGenerationId"] == records[0]["generation"]["generationId"]
    assert [entry["patchNotes"] for entry in records[1]["patchNotesHistory"]] == ["Pack B root.", "Pack B update."]


def test_record_only_stages_a_valid_cross_loader_mod_fixture(make_ctx):
    ctx = make_ctx()
    local = {
        "modId": "amp_autotest_conflict",
        "version": "1.0.0-local",
        "marker": "local",
    }
    server = {
        "modId": "amp_autotest_conflict",
        "version": "2.0.0-server",
        "marker": "server",
    }

    assert valid_mod_jar_bytes(local) != valid_mod_jar_bytes(server)
    runner._v_stage_modpack(
        ctx,
        {
            "recordOnly": True,
            "packId": "packbbb",
            "files": [{"path": "mods/amp-autotest-conflict.jar", "fixture": server}],
        },
    )

    records = list(
        (ctx.game_dir / "automodpack/client/records").glob("*/manifest.json")
    )
    assert len(records) == 1
    manifest = json.loads(records[0].read_text(encoding="utf-8"))
    metadata = manifest["groups"]["main"]["files"]["mods/amp-autotest-conflict.jar"]
    object_path = ctx.game_dir / "automodpack/client/data/objects" / metadata["sha1"]
    assert_valid_mod_fixture(object_path.read_bytes(), server, ctx.target.minecraft)


def test_record_only_generation_state_digest_matches_its_manifest(make_ctx):
    ctx = make_ctx(modpack_name="Pack B", marker_rel=Path("config/marker.json"))
    runner._v_stage_modpack(
        ctx,
        {
            "recordOnly": True,
            "packId": "packbbb",
            "packName": "Pack B",
            "files": [{"path": "config/b.txt", "content": "b"}],
        },
    )

    manifest_path = next(
        (ctx.game_dir / "automodpack/client/records").glob("*/manifest.json")
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    encoder = (
        CanonicalEncoder()
        .string("automodpack-state-v1")
        .string(manifest["modpackId"])
        .string(manifest["modpackName"])
        .string(manifest["automodpackVersion"])
        .string(manifest["loader"])
        .string(manifest["loaderVersion"])
        .string(manifest["mcVersion"])
        .integer(len(manifest["groups"]))
    )
    for group_id, group in sorted(manifest["groups"].items()):
        encoder.string(group_id).string(group["displayName"]).string(
            group["description"]
        ).string(group["category"]).string(group["icon"])
        encoder.boolean(group["required"]).boolean(group["defaultSelected"])
        for values in (group["breaksWith"], group["requires"]):
            encoder.integer(len(values))
            for value in sorted(values):
                encoder.string(value)
        encoder.integer(len(group["compatiblePlatforms"]))
        for platform in sorted(group["compatiblePlatforms"]):
            encoder.string(platform)
        encoder.integer(len(group["files"]))
        for logical_path, file in sorted(group["files"].items()):
            encoder.string(logical_path).long(int(file["size"])).string(
                file["type"]
            ).boolean(file["editable"])
            encoder.boolean(file["overwriteEditable"]).string(file["sha1"]).string(
                file["murmur"]
            )

    assert manifest["generation"]["stateDigest"] == encoder.digest()


# ── bootstrap fixture ───────────────────────────────────────────────────────


def test_seed_bootstrap_writes_live_fields(make_ctx):
    ctx = make_ctx()
    server_state = ctx.server_dir / "automodpack" / "server"
    server_state.mkdir(parents=True, exist_ok=True)
    (server_state / "current-projection.json").write_text(
        json.dumps({"modpackId": "packaaa"}), encoding="utf-8"
    )
    (ctx.server_dir / "automodpack" / "server-config.json").write_text(
        json.dumps({"connectionMode": "HOLEPUNCH"}), encoding="utf-8"
    )
    ctx.server_host = "amp-server"
    ctx.vars["fingerprint"] = "01:23:45"

    runner._v_seed_bootstrap(ctx, {})

    assert json.loads(
        (ctx.game_dir / "automodpack-bootstrap.json").read_text(encoding="utf-8")
    ) == {
        "origin": "amp-server:25565",
        "fingerprint": "01:23:45",
        "modpackId": "packaaa",
        "endpoint": "amp-server:25565",
        "connectionMode": "HOLEPUNCH",
    }
    assert ctx.vars["bootstrap_modpack_id"] == "packaaa"
