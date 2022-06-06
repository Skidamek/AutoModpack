# **Welcome to AutoModpack,**

# Key features
- Modpack hosting
- Easy download/update modpack (add mods, delete mods, add configs whatever you want)
- Your players no longer have to update mods.
- Update checker. (only for clients for now)

Coming soon
- Auto copy mods from mods installed on servers to modpack. 
- Config

# How to install

Download the latest version of the mod from [here](https://github.com/Skidamek/AutoModpack/releases/download/latest/AutoModpack.jar).

You need also download the fabric api if using fabric or Quilt Standard Libraries (QSL) if using quilt.

Put it into the `mods` folder of your minecraft/server installation.

Launch the game your game/server.

**- Client**

After join the server with modpack from this mod, mods will be automatically downloaded.

**- Server**

**Open port 30037 on your server! TCP/UDP**

Add mods/configs what ever you want in your modpack to the `./AutoModpack/modpack/` directory.

Restart server and you are good to go! Enjoy!

**If you want to delete some mods from modpack** 
- Go to `./AutoModpack/modpack/` directory on your server.
- Create file `delmods.txt`.
- Inside this file put names of mods you want to delete. (one name per line!)

# FAQ
- Which versions are supported?

1.18.x on fabric/quilt modloader. When 1.19 will be released, I will add support for 1.19.

- Do I must use official minecraft launcher?

No, you can use any launcher you want except for Feather Client its unsupported. (but launcher must be compatible with fabric/quilt modloader)

- Do I must install this mod on both sides?

Yes, you need to install this mod on both sides.



**Contributors are welcome**
[**click.**](CONTRIBUTING.md)
  
<p align="center"><img src="https://i.imgur.com/WQofabo.png" alt="Logo" width="200"></p>
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
