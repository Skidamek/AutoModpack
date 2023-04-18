<p align="center"><img src="https://i.imgur.com/zogBcIG.png" alt="Logo" width="200"></p>
<h1 align="center">AutoModpack  <br>
    <a href="https://www.curseforge.com/minecraft/mc-mods/automodpack"><img src="http://cf.way2muchnoise.eu/639211.svg" alt="CF"></a>
    <a href="https://github.com/Skidamek/AutoModpack/releases"><img src="https://img.shields.io/github/downloads/skidamek/automodpack/total?style=round&logo=github" alt="GitHub"></a>
    <a href="https://modrinth.com/mod/automodpack"><img src="https://img.shields.io/modrinth/dt/k68glP2e?logo=modrinth&label=&style=flat&color=242629" alt="Modrinth"></a>
    <br>
    <a href="https://fabricmc.net/"><img
            src="https://cdn.discordapp.com/attachments/705864145169416313/969720133998239794/fabric_supported.png"
            alt="Supported on Fabric"
            width="200"
        ></a>
        <a href="https://quiltmc.org/"><img
            src="https://cdn.discordapp.com/attachments/705864145169416313/969716884482183208/quilt_supported.png"
            alt="Supported on Quilt"
            width="200"
        ></a>
</h1>

Welcome to AutoModpack,

What is this?
AutoModpack is a Minecraft modification that aims to simplify the process of updating modpacks. With this mod, players no longer have to manually update each mod or the entire modpack. The tedious task of updating is handled automatically, making the experience more seamless and enjoyable.

[(A bit outdated) Showcase (YouTube video)](https://youtu.be/lPPzaNPn8g8)

I do not take credit for any of the content that can be downloaded from the mod. Likely all materials, except for the mod itself, were created by various talented individuals!

## Key features
- Auto modpack updating for seamless player experience.
- Effortless modpack management for admins, including the ability to easily manage mods, configs, resource packs, and more.
- Auto Synchronization of modpack content with server mods for consistency and reliability.
- Dynamic modpack updates without disrupting player experience.
- Quick and simple installation process.

## How it works?
On server AutoModpack generates a file called modpack-content.json on the server, which contains all the mods, configs, resource packs, and other necessary files for your modpack. The server also hosts this file, as well as all the files contained within it, on an HTTP server.

When a client joins the server, the modpack automatically downloads the modpack-content.json file from HTTP server and all the files contained within it, if they haven't been downloaded yet. The client simply needs to wait a few seconds for the files to download, install, and restart the game. Once the process is complete, the client can join the server again and enjoy the modpack!

## Is it secure?

Simply, no. This mod allows ANY server running it to put ANY file in your game folder, such as a virus or a keylogger. So if you are a client please make sure you trust your server administrator and as a good measure make sure to scan your games folders for malicious content. Please direct any useful security material to the github issues.

This is only intended for personal use. Other developers work very hard on their mods and simply visiting their website, modrinth/curseforge page, or github is just a common courtesy. Please don't use this to mass distribute other people's mods without explicit permisson. Depending on the copyright and/or pattent laws in your area using this mod with other developer's mods for a commercial purpose could be ILLEGAL, check licenses.


## How to use

First of all download the latest version of the mod from [here](https://modrinth.com/mod/automodpack/versions).

Put it into the `/mods/` folder of your minecraft/server installation.

Launch the game your game/server.

**- Client**

1. Join the server. Now modpack will be magically downloaded, installed. You will only need to click few buttons to confirm download and restart the game.
2. Join the server again and Enjoy!

**- Server**

1. Open port (default: `30037`) on your server! need to host modpack.
2. Restart server

and you are good to go!

**Want to add more content to your modpack?**
- To add mods to your server, place them in the `/automodpack/host-modpack/mods/` directory. 
And so analogically to add shaderpacks, put them in `/automodpack/host-modpack/shaderpacks/`. You can create any subdirectories you need within `/automodpack/host-modpack/` folder.
- Or add whatever file/folder from server main directory to the `syncedFiles` list in `/automodpack/automodpack-server.json`

**Want to delete some mods from modpack?**
- Just delete what you want from `/automodpack/host-modpack/` directory on your server.
- Or check and delete whatever file/folder from list `syncedFiles` in `/automodpack/automodpack-server.json`

**Want to exclude some mods from `syncedFiles`?**
- Just add them to the `"excludeSyncedFiles` list in `/automodpack/automodpack-server.json`


## Config

*/automodpack/automodpack-server.json*

| Name                        | Default Value                                                                                          | Description                                                                                                                                                                   |
|-----------------------------|--------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `modpackName`               |                                                                                                        | The name of the server modpack, currently doesn't do anything.                                                                                                                |
| `modpackHost`               | `true`                                                                                                 | Starts HTTP server to host modpack.                                                                                                                                           |
| `generateModpackOnStart`    | `true`                                                                                                 | Automatically generate modpack when the server starts.                                                                                                                        |
| `syncedFiles`               | `"/mods/", "/config/"`                                                                                 | A list of files and directories that will be synced from the default server directory to the modpack.                                                                         |
| `excludeSyncedFiles`        | `"/mods/iDontWantThisModInModpack.jar", "/config/andThisConfigToo.json", "/mods/andAllThisMods-*.jar"` | A list of *only* files that will be excluded from the syncing process. You can use wildcards `*` e.g. to exclude all files which starts with `mysupermod`, type `mysupermod*` |
| `allowEditsInFiles`         | `"/options.txt"`                                                                                       | List of files that clients are allowed to edit. In other words, just a files that are downloaded one time and then ignored from updating                                      |
| `optionalModpack`           | `false`                                                                                                | Whether or not the modpack is optional for clients to download.                                                                                                               |
| `autoExcludeServerSideMods` | `true`                                                                                                 | Automatically exclude server-side mods from the modpack.                                                                                                                      |
| `velocityMode`              | `false`                                                                                                | Enable support for the Velocity proxy, don't enable that if you don't use velocity! *Unstable*                                                                                |
| `hostPort`                  | `30037`                                                                                                | The port number on which the HTTP server listens.                                                                                                                             |
| `hostThreads`               | `8`                                                                                                    | The number of threads used by the HTTP server.                                                                                                                                |
| `hostIp`                    |                                                                                                        | The IP address on which the HTTP server binds.                                                                                                                                |
| `hostLocalIp`               |                                                                                                        | The local IP address on which the HTTP server binds.                                                                                                                          |
| `externalModpackHostLink`   |                                                                                                        | An external link to the modpack host, if it's hosted elsewhere. *Currently won't work!*                                                                                       |
| `selfUpdater`               | `true`                                                                                                 | Turn on/off all automodpack updates. This does not affect the mod's activity in installing modpacks.                                                                          |
| `downloadDependency`        | `true`                                                                                                 | Turn on/off auto-installing dependencies, such as Fabric API which is required for the automodpack.                                                                           |

*/automodpack/automodpack-client.json*

| Name                      | Default Value | Description                                                                                                                         |
|---------------------------|---------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `selectedModpack`         |               | The main folder of the modpack that you want to play. Typing the name of the modpack here will cause it to be loaded.               |
| `javaExecutablePath`      |               | The path to the Java executable that is used to relaunch Minecraft.                                                                 |
| `selfUpdater`             | `true`        | Turn on/off all automodpack updates. This does not affect the mod's activity in installing modpacks.                                |
| `downloadDependency`      | `true`        | Turn on/off auto-installing dependencies, such as Fabric API which is required for the automodpack.                                 |
| `autoRelaunchWhenUpdated` | `false`       | Auto relaunch Minecraft for seem lees automodpack self updating.                                                                    |


## Commands

- `/automodpack` - Status of automodpack and general help.
- `/automodpack generate` - Generate new modpack.content file on server.
- `/automodpack host` - Status of modpack hosting.
- `/automodpack host start` - Start modpack hosting.
- `/automodpack host stop` - Stop modpack hosting.
- `/automodpack host restart` - Restart modpack hosting.
- `/automodpack config reload` - Reload config files.


## Common problem

**Got errors every time you try to download the modpack / modpack-content is null?**

- Take a look at your server log after starting the server. See if there's a notice about the modpack host launch and make sure the IP is correct (it may only be the last digits of the IP).

- If the IP isn't right, head over to the config file and change the "hostIp" to your server's correct IP (without the port).

- Having trouble with the local host IP? No worries, just repeat the process but change "hostLocalIp" instead.

## Questions?
* Feel free to contact me via discord Skidam#8666

**Contributors are welcome!**
[**click.**](CONTRIBUTING.md)
