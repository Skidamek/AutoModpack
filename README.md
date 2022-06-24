# **Welcome to AutoModpack,**

# Key features
- Your players no longer have to update mods.
- Modpack hosting
- Easy download/update modpack (add mods, delete mods, add configs whatever you want)
- Sync modpack mods with server mods.
- Dynamically change mods in modpack.
- Update checker/downloader.

# How to use

At first download the latest version of the mod from [here](https://github.com/Skidamek/AutoModpack/releases/latest/).

Put it into the `mods` folder of your minecraft/server installation.

Launch the game your game/server.

NOTE: If game crashed at first launch don't worry. It crashed because you need to have installed fabric api / quilted fabric api, which is installed automatically before when that crash.

**- Client**

On join the server who has this mod, modpack will be automatically downloaded and installed. You will only need to restart the game to properly load the mods.

**- Server**

**Open port (default: `30037`) on your server! TCP/UDP** need to host modpack.

NOTE: if you can't open any port on your server, you can type in the config "external_modpack_host" to http/s address of download server it can be even MediaFire/Google Drive. You can upload there modpack.zip whose is automatically generated on your minecraft server at ./AutoModpack/modpack.zip

Add mods/configs what ever you want in your modpack to the `./AutoModpack/modpack/` directory.

Restart server and you are good to go! Enjoy!

**If you want to delete some mods from modpack** 
- Go to `./AutoModpack/modpack/` directory on your server.
- Create file `delmods.txt`.
- Inside this file put full names of mods you want to delete. (one name per line!)

# Config

**Use Cloth Config and Mod Menu mods to change settings while playing.**

Client side settings:

- `danger_screen`: `true` or `false` (default: `true`) - Show danger screen before downloading updates.
- `check_updates_button`: `true` or `false` (default: `true`) - Show "Check updates" button on Title Screen.
- `delete_modpack_button`: `true` or `false` (default: `true`) - Show "Delete modpack" button on Title Screen. (Button is only visible when some modpack is installed)

Server side settings:

- `modpack_host`": `true` or `false` (default: `true`) - Host http server for modpack. If this is disabled use "External host server".
- `clone_mods`: `true` or `false` (default: `true`) - Clone all mods from default mods folder on your server to the modpack. (you **can** add mods that you don't want to load on the server, delmods.txt **isn't** making automatically when some mods got updated or deleted)
- `sync_mods`: `true` or `false` (default: `false`) - Its the same as "Clone mods" but here all other mods will be deleted.(you **can't** add mods that you don't want to load on the server, delmods.txt **is** making automatically when some mods got updated or deleted)
- `host_port`: `integer` (default: `30037`) - At this port http server for hosting modpack will be running.
- `host_thread_count`: `integer` (default: `2`) - Http server will be use this amount of threads.
- `host_external_ip`: `whatever` (default: ``) - Http server will be use this external ip instead of default one. (OPTIONAL)
- `external_modpack_host`: `whatever` (default: ``) - Typed here http/s address will be used as external host server. This will automatically disable `modpack_host`. (OPTIONAL)

# Commands

- `/automodpack` - Check version of AutoModpack. (this mod)
- `/automodpack generate-modpack` - Generate new modpack.zip file on server.

# FAQ
- Which versions are supported?

1.19.x and 1.18.x on fabric/quilt modloader.

- Do I must use official minecraft launcher?

No, you can use any launcher you want except for Feather Client its unsupported. (but launcher must be compatible with fabric/quilt modloader)

- Do I must install this mod on both sides?

Yes, you need to install this mod on both sides.

- Does it deleting existing mods which are not in modpack but the client has them?

No, it doesn't. It will only delete mods which are written out in delmods.txt in your modpack.

- Does it automatically update my mods?

No, it doesn't, but if you want this cool feature take a look at [AutoPlug](https://www.spigotmc.org/resources/autoplug-automatic-updater-for-plugins-mods-server-java-itself.78414/). If you are using AutoPlug, I recommend you set `sync_mods` to `true` in AutoModpack config.


**Contributors are welcome**
[**click.**](CONTRIBUTING.md)
  
<p align="center"><img src="https://i.imgur.com/zogBcIG.png" alt="Logo" width="200"></p>
<h1 align="center">AutoModpack  <br><br>
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
