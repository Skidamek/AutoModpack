AutoModpack syncs the server's modpack to every player automatically. The server publishes a pack, clients verify and download it, and everyone stays on the same version. Players install the mod once; after that they just join the server.

<p align="center">
    <a href="https://www.curseforge.com/minecraft/mc-mods/automodpack"><img src="http://cf.way2muchnoise.eu/639211.svg" alt="CurseForge Downloads"></a>
    <a href="https://github.com/Skidamek/AutoModpack/releases"><img src="https://img.shields.io/github/downloads/skidamek/automodpack/total?style=round&logo=github" alt="GitHub Total Downloads"></a>
    <a href="https://modrinth.com/mod/automodpack"><img src="https://img.shields.io/modrinth/dt/k68glP2e?logo=modrinth&label=&style=flat&color=242629" alt="Modrinth Downloads"></a>
</p>

If you distribute a pack with AutoModpack, respect the licenses of the mods you include. Do not mass-distribute other people's mods without their permission, especially commercially.

## What it does

- The server generates a modpack from its own files and hosts it: mods, configs, resource packs, shaders, whatever the pack holds.
- A connecting client reviews the pack, confirms once, and downloads it. Files that exist on Modrinth or CurseForge are pulled from those platforms directly, so mod authors keep their download counts.
- After a restart the client runs the pack. Later changes apply on the following launches; a prompt appears only when an update lands while the game is already running.

## Security

A server you connect to can push arbitrary executable files into your game. Only accept modpacks from servers you trust. For a safety net, use a sandboxed launcher such as [Pandora](https://pandora.moulberry.com/), or on Linux a launcher installed through Flatpak.

The mod verifies the server's certificate fingerprint, encrypts and authorizes transfers, and shows what it is about to write before it writes anything. The authors and contributors of AutoModpack are not responsible for what a malicious server does with this power; by using the mod you accept that risk.

If you find a security issue, contact the author privately on [Discord](https://discordapp.com/users/464522287618457631), post in the [Discord server](https://discord.gg/hS6aMyeA9P), or open an issue on [GitHub](https://github.com/Skidamek/AutoModpack/issues).

## Getting started

0. Back up anything you care about in your Minecraft installation, or make a fresh instance if your launcher supports that.
1. Download AutoModpack from [Modrinth](https://modrinth.com/mod/automodpack), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/automodpack), or [GitHub releases](https://github.com/Skidamek/AutoModpack/releases).
2. Put the jar in the `mods/` folder of the server and of every client.
3. Start the server; it generates the initial modpack.
4. Connect with the mod installed and confirm the download.

The wiki covers groups, client-side-only mods, and the rest of the configuration: [documentation](https://moddedmc.wiki/en/project/automodpack/docs). Questions go to the [Discord server](https://discord.gg/hS6aMyeA9P) or [GitHub issues](https://github.com/Skidamek/AutoModpack/issues).

Prefer an installer? The [modified Fabric installer](https://github.com/Skidamek/AutoModpack-Installer/releases/tag/Latest) installs Fabric and AutoModpack together.
