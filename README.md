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
Basically, this is Minecraft modification with goal to make easier updates to your modpack. Your friends / players won't need to manually update all mods / modpack anymore. Thanks to this mod all this boring stuff is done automatically.

[Showcase (YouTube video)](https://youtu.be/lPPzaNPn8g8)

## Key features
- Your players no longer have to update mods manually.
- Modpack hosting.
- Easy manage as admin (your player's mods, configs, shaderpacks, resourcepacks, etc.)
- Sync modpack mods with server mods.
- Dynamically change content of your modpack without triggering your friends/players.

## How to use

First of all download the latest version of the mod from [here](https://modrinth.com/mod/automodpack/versions).

Put it into the `mods` folder of your minecraft/server installation.

Launch the game your game/server.

**- Client**

On join the server who has this mod, modpack will be automatically downloaded and installed. You will only need to restart the game to properly load the mods.

**- Server**

**Open port (default: `30037`) on your server! TCP/UDP** need to host modpack.

NOTE: if you can't open any port on your server, or you just care about server performance. Use Google Drive, upload there modpack.zip whose automatically generated on your minecraft server at `./AutoModpack/modpack.zip`. And the link type into the config "external_modpack_host".

(Unfortunately when using Google Drive or other download service/server `sync_mods` and update feature introduced in 2.4.0 will not work. I am working on it, if you know how to implement Google Drive upload feature, let me know :)  )

Add mods/configs whatever you want in your modpack to the `./AutoModpack/modpack/` directory.

Restart server and you are good to go! Enjoy!

**I want to delete some mods from my modpack**
- Go to `./AutoModpack/modpack/` directory on your server.
- Create file `delmods.txt`.
- Inside this file put full names of mods you want to delete. **(one full name per line!)**

**I want to exclude some mods from my modpack**
- Try to use `auto_exclude_server_side_mods` in config, if it doesn't work like you want to, make the steps below...
- Go to `./AutoModpack/blacklist.txt` file on your server.
- Inside this file put full names of mods you want to delete. **(one full name per line!)**

## Config

*./config/automodpack.properties*

Use Cloth Config and Mod Menu to change settings in game

**Client side**

| Name                    | Default Value | Description                                                                                                                       |
|-------------------------|---------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `danger_screen`         | `true`        | Show danger screen before downloading updates, also when this is disabled modpack can be updated in preload stage of loading game |
| `check_updates_button`  | `true`        | Show "Check updates" button on Title Screen                                                                                       |
| `delete_modpack_button` | `true`        | Show "Delete modpack" button on Title Screen                                                                                      |

**Server side**

| Name                                 | Default Value | Description                                                                                                                |
|--------------------------------------|---------------|----------------------------------------------------------------------------------------------------------------------------|
| `modpack_host`                       | `true`        | Host http server for modpack. If this is disabled use `external_modpack_host`                                              |
| `generate_modpack_on_launch`         | `true`        | Auto generation modpack at the every start of the server                                                                   |
| `sync_mods`                          | `true`        | Clone all your mods to modpack and delete all mods whose were deleted or updated                                           |
| `auto_exclude_server_side_mods`      | `true`        | Excluding all server side mods from modpack to prevent some crashes on client                                              |
| `only_optional_modpack`              | `false`       | Vanilla players will, can join if other mods on server are only server side                                                |
| `host_port`                          | `30037`       | This port will be used to host modpack                                                                                     |
| `host_thread_count`                  | `2`           | Modpack host will be use this amount of threads.                                                                           |
| `host_external_ip`                   | --            | Modpack host will be use this external ip instead of default one. (OPTIONAL)                                               |
| `host_external_ip_for_local_players` | --            | Same as `host_external_ip` but only for players from local network. (OPTIONAL)                                             |
| `external_modpack_host`              | --            | Typed here http/s address will be used as external host server. This will automatically disable `modpack_host`. (OPTIONAL) |


## Commands

- `/automodpack` - Check version of AutoModpack. (this mod)
- `/automodpack generate-modpack` - Generate new modpack.zip file on server.
- `/automodpack modpack-host` - Check if modpack is hosted.
- `/automodpack modpack-host start` - Start modpack hosting.
- `/automodpack modpack-host stop` - Stop modpack hosting.
- `/automodpack modpack-host restart` - Restart modpack hosting.

## FAQ

- Which versions are supported?

1.19.x and 1.18.x on fabric/quilt modloader.

- What is [CLIENT\] MODS folder?

Basically (It's for server admins and have it purpose only if `sync_mods` is enabled) you can add there those mods you want to add to the modpack, but you don't want to load them on server

- Do I must use official minecraft launcher?

No, you can use any launcher you want except for Feather Client its unsupported. (but launcher must be compatible with fabric/quilt modloader)

- Do I must install this mod on both sides?

Yes, you need to install this mod on both sides. (However if you enable only_optional_modpack in the config, player will be able to join without automodpack installed)

- Does it deleting existing mods which are not in modpack but the client has them?

No, it doesn't. It will only delete mods which are written out in delmods.txt in your modpack.

- Does it automatically update my mods?

No, it doesn't, but if you want this cool feature take a look at [AutoPlug](https://www.spigotmc.org/resources/autoplug-automatic-updater-for-plugins-mods-server-java-itself.78414/). If you are using AutoPlug, I recommend you set `sync_mods` to `true` in AutoModpack config.

## Common problems

- I get errors every time I try to download modpack!

Check if in the server log after start where is a notification about the modpack host launch, there is a correct ip (Its really can be last numbers of ip)
change in the config `host_external_ip` to your correct server ip (without port)

Follow the same process if you have a problem with the local host ip, but to `host_external_ip_for_local_players`

## Questions?
* Feel free to contact me via discord Skidam#8666

**Contributors are welcome!**
[**click.**](CONTRIBUTING.md)
