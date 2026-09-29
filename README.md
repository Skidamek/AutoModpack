**AutoModpack** synchronizes players with your server's modpack, automatically. Install it on the server and it builds a modpack from the files on your server, hosts it, and keeps it up to date. Install it on the client and joining the server gets you the exact same pack. No more trading zip files or hunting down exact mod versions. It's made for anyone running a modded server, whether that's for a few friends or a whole community.

One jar works on every supported Minecraft version, 1.18.2 through 26.3, and on Fabric, Forge, and NeoForge. The same jar runs on the server and the client.

<p align="center">
    <a href="https://youtu.be/lPPzaNPn8g8" target="_blank">
        <img src="https://img.youtube.com/vi/lPPzaNPn8g8/0.jpg" alt="AutoModpack Showcase Video (Outdated)" width="400">
    </a>
    <br>
    <i>(This showcase video above is a little outdated, but it gives you a good idea of what AutoModpack does!)</i>
</p>

<p align="center">
    <a href="https://www.curseforge.com/minecraft/mc-mods/automodpack"><img src="http://cf.way2muchnoise.eu/639211.svg" alt="CurseForge Downloads"></a>
    <a href="https://github.com/Skidamek/AutoModpack/releases"><img src="https://img.shields.io/github/downloads/skidamek/automodpack/total?style=round&logo=github" alt="GitHub Total Downloads"></a>
    <a href="https://modrinth.com/mod/automodpack"><img src="https://img.shields.io/modrinth/dt/k68glP2e?logo=modrinth&label=&style=flat&color=242629" alt="Modrinth Downloads"></a>
</p>

> **Disclaimer:** While AutoModpack is a powerful tool for managing modpacks, the content it downloads (mods, resource packs, etc.) is created by various talented developers. Please remember to respect their work and licenses. Don't use AutoModpack to mass-distribute content without explicit permission, especially for commercial purposes. Always check the licenses of the mods you include in your pack.

## How it works

1. The server builds the modpack from its own mods folder by default and hosts it. Anything else you want to ship, like client-side mods, configs, resource packs, or shader packs, goes in the server's `automodpack/host-modpack/main/` folder.
2. A player joins with the mod installed. Before downloading anything, the client verifies the server's identity. A certificate fingerprint the admin shares with players, a pinned join address, a CA-signed certificate, or a DNSSEC record all work. On a first connect, the player compares or pastes the fingerprint.
3. The client downloads the pack. Mods listed on Modrinth and CurseForge come straight from those platforms, so mod authors get the download credit. Everything else comes from the server over an encrypted connection.
4. Files land in a managed `automodpack` folder and load from there, and the player's own extra mods in the regular mods folder keep working. Sometimes one restart is needed, when the loader or game version changes or when files have to sit in the regular mods folder. The mod tells the player, closes the game, and the player starts it again.
5. From then on, every launch checks for updates, and most apply with no restart at all.

## Getting started

1. Make a backup of your Minecraft installation, or create a fresh instance if your launcher supports it.
2. Download AutoModpack from [GitHub](https://github.com/Skidamek/AutoModpack/releases), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/automodpack), or [Modrinth](https://modrinth.com/mod/automodpack).
3. Drop the jar into the server's `mods/` folder and start the server. AutoModpack generates the modpack and starts hosting it.
4. Drop the same jar into the client's `mods/` folder and connect to the server. That's typically all you need to do.

Prefer an all-in-one setup? Our [modified Fabric installer](https://github.com/Skidamek/AutoModpack-Installer/releases/tag/Latest) downloads AutoModpack alongside the Fabric loader.

The [documentation](https://moddedmc.wiki/en/project/automodpack/docs) has a start guide and covers server config, client-side-only mods, optional groups, resource packs, and [security setup](https://moddedmc.wiki/en/project/automodpack/latest/docs/security). If you get stuck, join the [Discord server](https://discord.gg/hS6aMyeA9P) or open an issue on [GitHub](https://github.com/Skidamek/AutoModpack/issues).

Coming from v4? Follow the [migration guide](https://moddedmc.wiki/en/project/automodpack/latest/docs/migrating-from-v4).

## Why AutoModpack

* No special launcher or third-party approval. AutoModpack is a regular mod, and your server hosts its own pack.
* Mod authors get the credit. Mods are downloaded straight from Modrinth and CurseForge wherever they're listed there.
* Verified transfers. The client checks the server's identity before the first download, and files move over an encrypted connection.
* Updates without restarts. Most updates need no restart, and when one does, the mod closes the game for you and you start it again.
* Optional groups. Split the pack into groups players can pick in-game, so one server can offer different setups to different players.
* Safety nets. Every publish can be rolled back, updates are crash-safe transactions, and the client keeps an instance timeline that can restore your files. The pack can only delete files it can prove it delivered.
* Server-side mods are filtered out. AutoModpack detects server-side mods and leaves them out of player downloads.

## Security and trust

The mod lets servers place files on players' computers, so only join servers you trust. A malicious administrator or a compromised server could hand out malware with the pack. For extra caution, run the game in a sandboxed launcher such as [Pandora](https://pandora.moulberry.com/), or on Linux use a launcher installed through Flatpak. The certificate check is what stops an impostor from giving players a different pack.

While AutoModpack tries to be as secure as possible, the creators and contributors are not responsible for any harm, damage, loss, or issues that may result from using the mod. **By using AutoModpack, you acknowledge and accept this risk.**

If you have security insights or concerns, please reach out. You can contact privately on [Discord](https://discordapp.com/users/464522287618457631), publicly on the [Discord server](https://discord.gg/hS6aMyeA9P), or open an issue on [GitHub](https://github.com/Skidamek/AutoModpack/issues).

## Supporters

AutoModpack wouldn't be where it is without the amazing community!

* All the [contributors](https://github.com/Skidamek/AutoModpack/graphs/contributors) who have helped improve the mod!
* [duckymirror](https://github.com/duckymirror), Juan, cloud, [Merith](https://github.com/Merith-TK), [SettingDust](https://github.com/SettingDust), Suerion, and griffin4cats for their invaluable help with testing, code, and ideas!
* HyperDraw for creating the mod icon!
* All the generous supporters on [Ko-fi](https://ko-fi.com/skidam), your support means the world!

## Contributing

Contributions are welcome: code, bug reports, documentation improvements, or just spreading the word. See [CONTRIBUTING.md](CONTRIBUTING.md) for details.
