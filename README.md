<p align="center"><img src="https://i.imgur.com/zogBcIG.png" alt="Logo" width="200"></p>
<h1 align="center">AutoModpack  <br>
    <a href="https://www.curseforge.com/minecraft/mc-mods/automodpack"><img src="http://cf.way2muchnoise.eu/639211.svg" alt="CF"></a>
    <a href="https://github.com/Skidamek/AutoModpack/releases"><img src="https://img.shields.io/github/downloads/skidamek/automodpack/total?style=round&logo=github" alt="GitHub"></a>
    <a href="https://modrinth.com/mod/automodpack"><img src="https://img.shields.io/modrinth/dt/k68glP2e?logo=modrinth&label=&style=flat&color=242629" alt="Modrinth"></a>
</h1>

What is this?
<br>
AutoModpack is a Minecraft modification that aims to simplify the process of updating modpacks for servers. With this mod, players no longer have to manually update each mod or the entire modpack. The tedious task of updating is handled automatically, making the gaming experience more seamless and enjoyable.

~~[(OUTDATED) Showcase (YouTube video)](https://youtu.be/lPPzaNPn8g8)

I do not take credit for any of the content that can be downloaded from the mod. Likely all materials, except for the mod itself, were created by various talented individuals!

## Key features
- Seamless modpack updates for players without disrupting experience of gaming.
- Effortless modpack management for admins, including the ability to easily manage mods, configs, resource packs, and more.
- Direct downloads from Modrinth and CurseForge.
- Quick and simple installation process.

## How it works?
On server AutoModpack generates a file called automodpack-content.json on the server, which contains all the mods, configs, resource packs, and other necessary files for your modpack. The server also hosts this file, as well as all the files contained within it, on an HTTP server.

When a client joins the server, the modpack automatically downloads the automodpack-content.json file from HTTP server, checks Modrinth and Curseforge APIs to get direct downloads of most if not all your mod/resourcepacks/shaders files and then it downloads all the files contained within content, if they haven't been installed before on client. The client simply needs to wait a few seconds for the files to download, install, and restart the game. Once the process is completed, the client can join the server again and enjoy the modpack

## Is it secure?

Simply, no. This mod allows ANY server running it to put ANY file in your game folder, such as a virus or a keylogger. So if you are a client please make sure you trust your server administrator and as a good measure make sure to scan your games folders for malicious content. Please direct any useful security material to the github issues. However as long you know server is safe and owner isn't impostor, you should be good.

This is only intended for personal use. Other developers work very hard on their mods and simply visiting their website, Modrinth/CurseForge page, or github is just a common courtesy. Please don't use this to mass distribute other people's mods without explicit permission. Depending on the copyright and/or pattent laws in your area using this mod with other developer's mods for a commercial purpose could be ILLEGAL, check licenses.


## How to use

First of all download the latest version of the mod from [here](https://modrinth.com/mod/automodpack/versions) or use our [modified version of fabric installer](https://github.com/Skidamek/AutoModpack-Installer/releases/tag/Latest) which will install fabric loader and AutoModpack.

Put it into the `/mods/` folder of your minecraft/server installation.

Launch the game your game/server.

**- Client**

1. Join the server.
2. Wait for the modpack to download and install.
3. Restart the game.
4. Done.

**- Server**

1. Start the server.
2. Done.
3. (Optional) Configure the automodpack to your liking.

#### Do you want to add more content to your modpack?
- By default, the modpack will automatically synchronize all mods, configs from default server directories to the modpack. (Check `syncedFiles` list in `/automodpack/automodpack-server.json`)
- To add more mods to your server, place them in the `/automodpack/host-modpack/main/mods/` directory.
  And so analogically to add shaderpacks, put them in `/automodpack/host-modpack/main/shaderpacks/`. You can create any subdirectories you need within `/automodpack/host-modpack/main/` folder.
- Or add whatever file/folder from server main directory to the `syncedFiles` list in `/automodpack/automodpack-server.json`

#### Do you want to delete some mods from modpack?
- Just delete what file you want to delete from directory on your server.
- Or check and delete whatever file/folder from list `syncedFiles` in `/automodpack/automodpack-server.json`

#### Do you want to exclude some mods from `syncedFiles`?
- Just add `!` before the file/folder name in `syncedFiles` which you want to exclude.


## Config

*/automodpack/automodpack-server.json*

| Name                         | Default Value                                                                                                                                    | Description                                                                                                                                                                                                                                                       |
|------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `modpackName`                |                                                                                                                                                  | The name of the server modpack, shows while downloading modpack, more in the near future!                                                                                                                                                                         |
| `modpackHost`                | `true`                                                                                                                                           | Starts HTTP server to host modpack.                                                                                                                                                                                                                               |
| `generateModpackOnStart`     | `true`                                                                                                                                           | Automatically generate modpack when the server starts.                                                                                                                                                                                                            |
| `syncedFiles`                | `"/mods/*.jar", "!/mods/iDontWantThisModInModpack.jar", "!/config/andThisConfigToo.json", "!/mods/andAllTheseMods-*.jar", "!/mods/server-*.jar"` | A list of files and directories that will be synced from the default server directory to the modpack.                                                                                                                                                             |
| `allowEditsInFiles`          | `"/options.txt", "/config/*", "!/config/excludeThisFile"`                                                                                        | A list of *only* files that clients are allowed to edit. In other words, just a files that are downloaded one time and then ignored from updating. There you can also use wildcards.                                                                              |
| `requireAutoModpackOnClient` | `true`                                                                                                                                           | Whether or not this mod is optional for clients to join server.                                                                                                                                                                                                   |
| `autoExcludeServerSideMods`  | `true`                                                                                                                                           | Automatically excludes server-side mods from the modpack.                                                                                                                                                                                                         |
| `hostModpackOnMinecraftPort` | `true`                                                                                                                                           | Injects into minecraft network IO thanks to which modpack hosting doesn't require any additional port.                                                                                                                                                            |
| `hostIp`                     |                                                                                                                                                  | The IP address on which the HTTP server binds.                                                                                                                                                                                                                    |
| `hostLocalIp`                |                                                                                                                                                  | The local IP address on which the HTTP server binds.                                                                                                                                                                                                              |
| `updateIpsOnEveryStart`      | `false`                                                                                                                                          | Updates `hostIp` and `hostLocalIp` on every server start. Might be useful if you have dynamic IP address and you know that automodpack gets you ip correctly (try and see :) ).                                                                                   |
| `hostPort`                   | `-1`                                                                                                                                             | The port number on which the HTTP server listens.                                                                                                                                                                                                                 |
| `reverseProxy`               | `false`                                                                                                                                          | If `true` AutoModpack won't be adding configurable port from `hostPort` to the end of `hostIp`, `localHostIp` and `externalModpackHostLink`.                                                                                                                      |
| `selfUpdater`                | `false`                                                                                                                                          | Turn on/off all automodpack updates. This does not affect the mod's activity in installing modpacks.                                                                                                                                                              |
| `acceptedLoaders`            | `"<mod loader used by server>"`                                                                                                                  | Allows players from different modloaders to connect to your server (as long, automodpack support that loader and other mods on your server aren't incompatible with each other) with the same modpack. (use with caution, some mods may not work on both loaders) |

*/automodpack/automodpack-client.json*

| Name                | Default Value | Description                                                                                                           |
|---------------------|---------------|-----------------------------------------------------------------------------------------------------------------------|
| `selectedModpack`   |               | The main folder of the modpack that you want to play. Typing the name of the modpack here will cause it to be loaded. |
| `installedModpacks` |               | A list of modpacks that are installed on the client.                                                                  |
| `selfUpdater`       | `false`       | Turn on/off all automodpack updates. This does not affect the mod's activity in installing modpacks.                  |


## Commands

- `/automodpack` - Status of automodpack and general help.
- `/automodpack generate` - Generate new modpack-content.json file on server, which results in modpack update for clients.
- `/automodpack host` - Status of modpack hosting.
- `/automodpack host start` - Start modpack hosting.
- `/automodpack host stop` - Stop modpack hosting.
- `/automodpack host restart` - Restart modpack hosting.
- `/automodpack config reload` - Reload config files.


## Questions? Problems?
* Feel free to contact me via discord: [skidam](https://discordapp.com/users/464522287618457631)

Thanks to [**@Fallen-Breath**](https://github.com/Fallen-Breath) for awesome [mod template](https://github.com/Fallen-Breath/fabric-mod-template/)

**Contributors are welcome!**
[**see**](CONTRIBUTING.md)
