AutoModpack syncs the server's modpack to every player automatically. The server publishes a pack, clients verify and download it, and everyone stays on the same version. Players install the mod once; after that they just join the server.

<p align="center">
    <a href="https://www.curseforge.com/minecraft/mc-mods/automodpack"><img src="http://cf.way2muchnoise.eu/639211.svg" alt="CurseForge Downloads"></a>
    <a href="https://github.com/Skidamek/AutoModpack/releases"><img src="https://img.shields.io/github/downloads/skidamek/automodpack/total?style=round&logo=github" alt="GitHub Total Downloads"></a>
    <a href="https://modrinth.com/mod/automodpack"><img src="https://img.shields.io/modrinth/dt/k68glP2e?logo=modrinth&label=&style=flat&color=242629" alt="Modrinth Downloads"></a>
</p>

One jar covers every supported game version, 1.18.2 through 26.3 on Fabric, Forge, and NeoForge, client and server, on Windows, Linux, and macOS. The jar detects the loader and game version at startup.

If you distribute a pack with AutoModpack, respect the licenses of the mods you include. Do not mass-distribute other people's mods without their permission, especially commercially.

## Building the pack

The pack is declared in `automodpack/server.conf` and built from two sources. A group's directory under `automodpack/host-modpack/<id>/` ships in full: client-side mods, configs, shaderpacks, resource packs, anything the server itself has no use for. `from-server` rules pull files straight out of the server root, which is how the mods and configs the server already runs join the pack. `exclude` rules keep anything else out, and server-side mods are dropped automatically unless you say otherwise.

The default `main` group is required; extra groups are optional parts. Each optional group carries a display name and a description, sits under a category in the in-game Group Selection screen, and can declare `requires`, `breaks-with`, and `compatible-platforms`. A shader config can require its shader pack, two mods that replace the same files can declare the conflict, and a Windows-only group can say so. The `modpack` section also carries the pack's voice: `name`, a `description`, a `flavor` line shown while the client downloads, and an `accent` color for the progress bar.

Publish a new generation with `/automodpack generate`. A server restart publishes automatically when the content changed.

## What a player gets

Connecting to a server with a pack shows one review first: AutoModpack verifies the server, lists what it is about to install, and downloads after a single confirmation. Files matched on Modrinth or CurseForge download from those platforms' own CDNs; everything else comes over the host's TLS 1.3 connection, compressed per request, resuming at the byte a download stopped at.

On later launches the client fetches the current generation before the game loads anything. Config and resource pack changes apply in place, with no restart; mod or loader changes ask for one relaunch. Files marked `editable` (`options.txt` and `config/**` by default) keep the player's own copy: edits survive every update, and removing one keeps it removed until the server changes the file. When a pack file does overwrite local bytes, the instance timeline keeps the old copy one restore away. A file leaves the game only when the pack's ownership ledger proves AutoModpack put it there, and `saves/`, `screenshots/`, and `logs/` are never touched.

AutoModpack follows the server too: clients take on the server's mod version through Modrinth on the next restart.

## Hosting

The default HOLEPUNCH mode serves the pack over your Minecraft server's own port. It needs no extra ports or extra software, and unmarked logins reach Minecraft unchanged. A dedicated listener, plain HTTPS behind your own domain or a Cloudflare tunnel, and a static export for nginx or any S3-compatible bucket all serve the same bytes to the client. When `validate-secrets` is on, every download request carries a login-issued bearer secret, and `bandwidthLimit` caps upload speed per client.

Drop an `.ogg` file at `automodpack/host-modpack/waiting-music.ogg` and clients play it while they download.

## Trusting a server

A server you connect to can push arbitrary executable files into your game. The client verifies who it talks to before anything downloads, in a ladder: a saved pin is law, a CA-signed certificate covering the address you typed trusts silently, a DNSSEC AMP1 record published by the operator trusts silently, and anything else asks you to compare the fingerprint by hand. The first install also reports whether every mod was matched on Modrinth or CurseForge; jars that were not need an explicit risk acknowledgement.

Only accept modpacks from servers you trust. For a safety net, use a sandboxed launcher such as [Pandora](https://pandora.moulberry.com/), or on Linux a launcher installed through Flatpak.

The authors and contributors of AutoModpack are not responsible for what a malicious server does with this power; by using the mod you accept that risk.

If you find a security issue, contact the author privately on [Discord](https://discordapp.com/users/464522287618457631), post in the [Discord server](https://discord.gg/hS6aMyeA9P), or open an issue on [GitHub](https://github.com/Skidamek/AutoModpack/issues).

## Getting started

0. Back up anything you care about in your Minecraft installation, or make a fresh instance if your launcher supports that.
1. Download AutoModpack from [Modrinth](https://modrinth.com/mod/automodpack), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/automodpack), or [GitHub releases](https://github.com/Skidamek/AutoModpack/releases).
2. Put the jar in the `mods/` folder of the server and of every client.
3. Start the server; it generates the initial modpack.
4. Connect with the mod installed and confirm the download.

The wiki covers groups, client-side-only mods, and the rest of the configuration: [documentation](https://moddedmc.wiki/en/project/automodpack/docs). Questions go to the [Discord server](https://discord.gg/hS6aMyeA9P) or [GitHub issues](https://github.com/Skidamek/AutoModpack/issues).

Prefer an installer? The [modified Fabric installer](https://github.com/Skidamek/AutoModpack-Installer/releases/tag/Latest) installs Fabric and AutoModpack together.
