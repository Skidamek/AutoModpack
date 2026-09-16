
**AutoModpack** is a Minecraft mod that synchronizes players with the server's modpack automatically. Install it on the server and on the client, and everyone stays on the same mods, configs, and resource packs without anyone copying files around.

<p align="center">
    <a href="https://youtu.be/lPPzaNPn8g8" target="_blank">
        <img src="https://img.youtube.com/vi/lPPzaNPn8g8/0.jpg" alt="AutoModpack Showcase Video (Outdated)" width="400">
    </a>
    <br>
    <i>(This showcase video is a little outdated, but it gives a good idea of what AutoModpack does.)</i>
</p>

<p align="center">
    <a href="https://www.curseforge.com/minecraft/mc-mods/automodpack"><img src="http://cf.way2muchnoise.eu/639211.svg" alt="CurseForge Downloads"></a>
    <a href="https://github.com/Skidamek/AutoModpack/releases"><img src="https://img.shields.io/github/downloads/skidamek/automodpack/total?style=round&logo=github" alt="GitHub Total Downloads"></a>
    <a href="https://modrinth.com/mod/automodpack"><img src="https://img.shields.io/modrinth/dt/k68glP2e?logo=modrinth&label=&style=flat&color=242629" alt="Modrinth Downloads"></a>
</p>

> **Disclaimer:** The content AutoModpack downloads (mods, resource packs, and so on) is created by various developers. Respect their work and licenses, and check them before including content in your pack, especially for commercial use.

## What it does

- **Automatic syncing.** Players connect and receive the server's modpack. Updates apply on launch, and a restart is only needed when mods in the standard folder or the loader version change.
- **No third-party service.** The server hosts its own pack. No launcher lock-in, no approval process.
- **Anything can ship.** Mods, configs, resource packs, shader packs, kubejs scripts, anything that fits the game directory layout.
- **Downloads respect mod authors.** Where possible, files download directly from Modrinth and CurseForge, so authors see every download.
- **Secure and compact.** Downloads run over verified TLS, only authorized players can fetch the pack, and transfers are compressed.

## How it works

The server scans its files, builds a modpack manifest, and hosts it together with the file contents. When a client connects:

1. It verifies the server's certificate before accepting anything. See the [security documentation](https://moddedmc.wiki/en/project/automodpack/docs/security).
2. It resolves direct download links from Modrinth and CurseForge where the files exist there.
3. It downloads everything into a content-addressed store and a managed projection.
4. After a game restart, the pack is loaded and the client matches the server.

On later launches, AutoModpack fetches the server's current state and applies changes before the game loads. An update caught while you are already in-game is applied through a review screen, with a restart only when it is actually required.

## Security

This mod lets servers download files, including executable ones, onto player machines. Only connect to servers you trust, because a malicious or compromised server can ship harmful files. Sandboxed launchers such as [Pandora](https://pandora.moulberry.com/) reduce that risk, and on Linux, launchers installed through Flatpak run the game in a sandbox.

AutoModpack verifies server identities through CA certificates, DNSSEC fingerprints, or pinned addresses, and it keeps unauthorized players from downloading through per-player secrets. The details are in the [security documentation](https://moddedmc.wiki/en/project/automodpack/docs/security).

The authors and contributors of AutoModpack are not responsible for harm, damage, or loss resulting from using the mod. **By using AutoModpack, you accept this risk.**

Security insights or concerns are welcome: contact [the author on Discord](https://discordapp.com/users/464522287618457631), post in the [Discord server](https://discord.gg/hS6aMyeA9P), or open an [issue on GitHub](https://github.com/Skidamek/AutoModpack/issues).

## Getting started

0. If the Minecraft installation holds anything valuable, make a backup first. On a launcher with multiple instances, create a fresh one for AutoModpack.
1. Download AutoModpack from [GitHub releases](https://github.com/Skidamek/AutoModpack/releases), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/automodpack), or [Modrinth](https://modrinth.com/mod/automodpack).
2. Put the jar into the `mods` folder of the server and of the client.
3. Start the server so AutoModpack can generate the initial modpack.
4. Connect from the client.

That is the whole setup. To customize the pack with client-side mods and configs, follow the [documentation](https://moddedmc.wiki/en/project/automodpack/docs), starting with the quick start guide. If something does not work, join the [Discord server](https://discord.gg/hS6aMyeA9P) or open an issue on [GitHub](https://github.com/Skidamek/AutoModpack/issues).

Prefer an all-in-one setup? The [modified Fabric installer](https://github.com/Skidamek/AutoModpack-Installer/releases/tag/Latest) installs Fabric and AutoModpack together.

## Thanks

* All the [contributors](https://github.com/Skidamek/AutoModpack/graphs/contributors) who improved the mod.
* **duckymirror**, Juan, cloud, **[Merith](https://github.com/Merith-TK)**, **[SettingDust](https://github.com/SettingDust)**, Suerion, and griffin4cats for testing, code, and ideas.
* **HyperDraw** for the mod icon.
* The supporters on [Ko-fi](https://ko-fi.com/skidam).

## Contributing

Contributions are welcome: code, bug reports, documentation, everything helps. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, format, and submit changes.
