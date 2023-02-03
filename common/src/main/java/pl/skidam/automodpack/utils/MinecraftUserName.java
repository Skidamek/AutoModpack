package pl.skidam.automodpack.utils;

public class MinecraftUserName {
    private static String username;

    public static String get() {

        if (username != null) { //TODO check also in command from relauncher class, there also can be found player username
            return username;
        }

//        if (Platform.getEnvironmentType().equals("SERVER")) return null;
//
//        if (MinecraftClient.getInstance() != null) {
//            String username = MinecraftClient.getInstance().getSession().getUsername();
//            AutoModpack.clientConfig.username = username;
//            ConfigTools.saveConfig(AutoModpack.clientConfigFile, AutoModpack.clientConfig);
//            return MinecraftUserName.username = username;
//        } else if (AutoModpack.clientConfig.username != null || !AutoModpack.clientConfig.username.equals("")) {
//            return AutoModpack.clientConfig.username;
//        } else {
//            if (System.getProperties().contains("user.name")) {
//                return "(" + System.getProperty("user.name") + ")"; // lol    pov admin: reads console... Jan Kowalski is downloading modpack XD
//            } else {
//                return null;
//            }
//        }
        return null;
    }
}