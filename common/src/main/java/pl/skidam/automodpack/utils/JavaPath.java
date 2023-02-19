package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.config.ConfigTools;

import java.io.File;

import static pl.skidam.automodpack.StaticVariables.*;

public class JavaPath {
    public static String getJavaPath() {
        String javaPath;

        if (clientConfig.javaExecutablePath != null && !clientConfig.javaExecutablePath.equals("")) {
            javaPath = clientConfig.javaExecutablePath;
        } else {
            javaPath = System.getProperty("java.home");
        }

        javaPath = new File(javaPath).getAbsolutePath().replace("/./", "/");

        javaPath = javaPath.replace("\\", "/");

        if (!javaPath.endsWith("/bin/java") && !javaPath.endsWith("/bin/java/") && !javaPath.endsWith("/bin/java.exe") && !javaPath.endsWith("/bin/java.exe/")) {
            javaPath += javaPath.endsWith("/bin") ? "/java" : javaPath.endsWith("/") ? "bin/java" : "/bin/java";
        }

        javaPath = javaPath.replace("\\", "/");

        File javaFile = new File(javaPath);

        if (javaPath.contains(System.getProperty("java.home").replace("\\", "/")) || (javaFile.isFile() && javaFile.canExecute())) {
            clientConfig.javaExecutablePath = javaPath;
            ConfigTools.saveConfig(clientConfigFile, clientConfig);
        } else {
            LOGGER.error("Java executable path is invalid!");
        }

        return javaPath;
    }
}
