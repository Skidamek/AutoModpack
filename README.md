<p align="center"><img src="https://i.imgur.com/hcYOgtJ.png" alt="Logo" width="200"></p>
<h1 align="center">AutoModpack  <br>
    <a href="https://www.curseforge.com/minecraft/mc-mods/automodpack"><img src="http://cf.way2muchnoise.eu/639211.svg" alt="CF"></a>
    <a href="https://github.com/Skidamek/AutoModpack/releases"><img src="https://img.shields.io/github/downloads/skidamek/automodpack/total?style=round&logo=github" alt="GitHub"></a>
    <a href="https://modrinth.com/mod/automodpack"><img src="https://img.shields.io/modrinth/dt/k68glP2e?logo=modrinth&label=&style=flat&color=242629" alt="Modrinth"></a>
</h1>

**AutoModpack** is a Minecraft modification that aims to simplify the process of managing modpacks. Designed for private **servers**. With this mod, players no longer have to manually update each mod or the entire modpack to achive parity with the server. This tedious task is handled automatically, making the gaming experience more seamless and enjoyable.

[(OUTDATED) Showcase (YouTube video)](https://youtu.be/lPPzaNPn8g8)

I do not take credit for any of the content that can be downloaded from the mod. Likely all materials, except for the mod itself, were created by various talented individuals!

## Key Features
- Seamless modpack updates for players without disrupting experience of gaming.
- Effortless modpack management for admins, including the ability to easily manage mods, configs, resource packs, and more.
- Direct downloads from Modrinth and CurseForge.
- Quick and simple installation process.

## How It Works
On server AutoModpack generates a metadata file, which contains all the mods, configs, resource packs, and any other files of your modpack. Server hosts this file, as well as all the files contained within it, on an HTTP server.

When a client joins the server, the AutoModpack consumes the metadata file, fetches Modrinth and Curseforge APIs to get direct downloads of most if not all your mod/resourcepacks/shaders files and then it downloads all the modpack files. Once the process is completed, client needs to restart game and then can join the server enjoying the modpack.

Also on every boot client checks if there're any updates to modpack, if so it updates and loads modpack without any additional restart of the game.

## "With great power comes great responsibility"

This mod allows ANY server running it to put ANY file in your game folder, such as a virus. So if you use it please make sure you trust your server administrator. Please direct any useful security material to the github issues. However as long you know server is safe and owner isn't an impostor, you *should* be good.

## Disclaimer

Other mod developers may work very hard on their mods and simply visiting their website, Modrinth/CurseForge page is just a common courtesy. Please don't use this to mass distribute other people's mods without explicit permission. Depending on the copyright and/or pattent laws in your area using this mod distributing other developer's mods for a commercial purpose could be ILLEGAL, check licenses.


## How To Use

Installation process is same as for any other mod. Just put it into the `/mods/` folder of your minecraft/server installation.

Or use our [modified version of fabric installer](https://github.com/Skidamek/AutoModpack-Installer/releases/tag/Latest) which will install fabric loader and AutoModpack.

Optionally configure automodpack to your liking.

**Please read the [wiki](https://github.com/Skidamek/AutoModpack/wiki) for more information.**


## Credits
Thanks to:
- All the [contributors](https://github.com/Skidamek/AutoModpack/graphs/contributors)!
- Special thanks to Juan, cloud, [Merith](https://github.com/Merith-TK), [SettingDust](https://github.com/SettingDust), Suerion and griffin4cats as well as everyone else who helped with testing, code or ideas!
- HyperDraw for amazing mod icon!
- [Fallen-Breath](https://github.com/Fallen-Breath) for [mod template](https://github.com/Fallen-Breath/fabric-mod-template/)
- All the supporters on [Ko-fi](https://ko-fi.com/skidam)

**Contributors are welcome!**
[**see**](CONTRIBUTING.md)
