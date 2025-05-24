<h1 align="center">
    ✨ AutoModpack ✨
</h1>
<p align="center">
    Tired of modpack update headaches? AutoModpack takes care of it!
    <br/>
    <br/>
    <a href="https://www.curseforge.com/minecraft/mc-mods/automodpack"><img src="http://cf.way2muchnoise.eu/639211.svg" alt="CurseForge Downloads"></a>
    <a href="https://github.com/Skidamek/AutoModpack/releases"><img src="https://img.shields.io/github/downloads/skidamek/automodpack/total?style=round&logo=github" alt="GitHub Total Downloads"></a>
    <a href="https://modrinth.com/mod/automodpack"><img src="https://img.shields.io/modrinth/dt/k68glP2e?logo=modrinth&label=&style=flat&color=242629" alt="Modrinth Downloads"></a>
</p>

---

## 🤯 Ditch the Update Hassle, Dive into Gameplay!

**AutoModpack** is the ultimate Minecraft modification designed to **revolutionize private server modpack management**. Say goodbye to the days of struggling to get everyone on the same version! This mod **automatically synchronizes** players with the server's modpack, making playing with friends incredibly smooth and hassle-free.

<p align="center">
    <a href="https://youtu.be/lPPzaNPn8g8" target="_blank">
        <img src="https://img.youtube.com/vi/lPPzaNPn8g8/0.jpg" alt="AutoModpack Showcase Video (Outdated)" width="400">
    </a>
    <br>
    <i>(Heads up! This showcase video is a little outdated, but it gives you a good idea of what AutoModpack does!)</i>
</p>

<br>

> **Disclaimer:** While AutoModpack is a powerful tool for managing modpacks, the content it downloads (mods, resource packs, etc.) is created by various talented developers. Please remember to respect their work and licenses. Don't use AutoModpack to mass-distribute content without explicit permission, especially for commercial purposes. Always check the licenses of the mods you include in your pack.

## 🔥 Why AutoModpack is Your New Best Friend

This isn't just another mod; it's a game-changer for private servers. Here's why:

*   🔌 **Plug-'n'-Play:** Install the mod, and you're done! Live in perfect sync with the server's modpack forever.
*   🔄 **Seamless Player Updates:** Players get the latest modpack updates automatically, without manual downloads or disruptions.
*   🚀 **Effortless Admin Management:** Easily manage mods, configs, resource packs, shaders, and more. Your modpack, your control.
*   ⚡️ **Direct & Respectful Downloads:** The mod pulls directly from Modrinth and CurseForge APIs, so mod authors get credit for every download.
*   🔒 **Secure & Speed:** Encrypted, authorized, compressed, quick modpack downloads.

## 🛠️ How the Magic Happens

AutoModpack works by generating a modpack (**metadata file**) on the server, which contains all the files of your modpack. The server then hosts this file and the modpack files.

When a client connects to the server:

1.  Connection; AutoModpack establishes a secure connection and prompts you to [verify the server's certificate fingerprint](https://moddedmc.wiki/en/project/automodpack/docs/technicals/certificate).
2.  Direct links; Fetches the APIs for direct downloads of your modpack's files from Modrinth and CurseForge, where possible (mods, resource packs, shaders).
3.  Modpack download; All files are downloaded to the client's automodpack folder.
4.  Game restart; AutoModpack loads the modpack, and the client is perfectly synced and ready to play!

On subsequent game launches, AutoModpack checks for updates. If changes are detected, it updates the modpack in the background—no additional restarts are required!

## ⚠️ Security and Trust - Read This!

> With great power comes great responsibility.

Because it downloads files directly into your game folder, it's crucial to **only use it on servers you absolutely trust**. A malicious server (administrator) *can* include harmful files.

While AutoModpack itself tries to be as secure as possible, due to the nature of the internet, the creators and contributors of AutoModpack are not responsible for any harm, damage, loss, or issues that may result from files downloaded from a server you connect to using the mod. **By using AutoModpack, you acknowledge and accept this risk.**

**If you have valuable security insights or concerns, please reach out!** You can contact privately on [Discord](https://discordapp.com/users/464522287618457631) or publicly on [Discord server](https://discord.gg/hS6aMyeA9P) or just open an issue on [GitHub](https://github.com/Skidamek/AutoModpack/issues).
## 🚀 Getting Started is a Breeze!

Installing AutoModpack is as simple as installing any other mod.

1.  Download the AutoModpack from the releases page on [GitHub](https://github.com/Skidamek/AutoModpack/releases), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/automodpack), or [Modrinth](https://modrinth.com/mod/automodpack).
2.  Place the downloaded file into the `/mods/` folder of both your server and client Minecraft installations.
3.  Start your server and let AutoModpack generate the initial modpack metadata.
4.  Connect to your server with the mod installed on your client.

That's typically all you need to do! AutoModpack will automatically create the modpack from your server's mods.

**Want to customize your modpack further?** Add configs, client-side-only mods, and more? **Check out the [wiki]https://moddedmc.wiki/en/project/automodpack/docs)!** It *hopefully* has all the details you need to tailor AutoModpack to your specific needs. If you encounter any issues or have questions, feel free to [join Discord server](https://discord.gg/hS6aMyeA9P) or open an issue on [GitHub](https://github.com/Skidamek/AutoModpack/issues).

Prefer an all-in-one solution? You can also use our [modified Fabric installer](https://github.com/Skidamek/AutoModpack-Installer/releases/tag/Latest) which downloads AutoModpack alongside the Fabric loader.

## 🙏 Huge Thanks to Our Supporters!

AutoModpack wouldn't be where it is without the amazing community!

*   **All the fantastic [contributors](https://github.com/Skidamek/AutoModpack/graphs/contributors)** who have helped improve the mod!
*   **[duckymirror](https://github.com/duckymirror), Juan, cloud, [Merith](https://github.com/Merith-TK), [SettingDust](https://github.com/SettingDust), Suerion, and griffin4cats** for their invaluable help with testing, code, and ideas!
*   **HyperDraw** for creating the awesome mod icon!
*   **All the generous supporters on [Ko-fi](https://ko-fi.com/skidam)** - your support means the world!

## 💖 Contribute and Make AutoModpack Even Better!

We love contributions! Whether it's code, bug reports, documentation improvements, or just spreading the word, your help is welcome.

**Ready to contribute? See our [CONTRIBUTING.md](CONTRIBUTING.md) for details!**
