package pl.skidam.automodpack_core;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import static pl.skidam.automodpack_core.GlobalVariables.serverConfig;
import static pl.skidam.automodpack_core.GlobalVariables.serverConfigFile;

public class Server {

    // TODO Finish this class that it will be able to host the server without mod
    public static void main(String[] args) {
        System.out.println("Hello, world!");

        serverConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFields.class);

        serverConfig.hostPort = 30037;
        serverConfig.hostModpackOnMinecraftPort = false;

        // TODO change it
//        HttpServer httpServer = new HttpServer(new ArrayList<>());
//
//        try {
//            Optional<ChannelFuture> serverChannel = httpServer.start();
//            serverChannel.orElseThrow().channel().closeFuture().sync();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }

        // initialize config or create if not exists

    }

    public static void setupServer() {
        // setup server
    }
}
