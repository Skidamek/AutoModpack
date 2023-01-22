package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.config.ConfigTools;

import java.io.File;

public class JavaPath {
    public static String getJavaPath() {
        String javaPath;

//        javaPath = executeCommand();

//        AutoModpack.LOGGER.error("Java path: " + javaPath);


        if (AutoModpack.clientConfig.javaExecutablePath != null && !AutoModpack.clientConfig.javaExecutablePath.equals("")) {
            javaPath = AutoModpack.clientConfig.javaExecutablePath;
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
            AutoModpack.clientConfig.javaExecutablePath = javaPath;
            ConfigTools.saveConfig(AutoModpack.clientConfigFile, AutoModpack.clientConfig);
        } else {
            AutoModpack.LOGGER.error("Java executable path is invalid!");
        }

        return javaPath;
    }

    public static boolean checkJavaPath(File file) {
//        String javaPath = getJavaPath();
//
//        File javaFile = new File(javaPath);
//
//        if (javaFile.isFile() && javaFile.canExecute())  {
            return true;
//        } else {
//            LOGGER.error("The Java executable was not found or the file {} is not executable. Please set the path to the Java executable manually in the automodpack client config file or add permission to execute the file.", javaPath);
//            return false;
//        }
    }


//    public static String executeCommand() {
//        StringBuilder output = new StringBuilder();
//        Process p;
//        String[] cmd = { "/bin/bash", "-c", "which java" };
//        try {
//            p = Runtime.getRuntime().exec(cmd);
//            int exitStatus = p.waitFor();
//            if (exitStatus == 0) {
//                System.out.println("Process completed successfully.");
//                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
//
//                String line;
//                while ((line = reader.readLine()) != null) {
//                    output.append(line).append("\n");
//                }
//
//            } else {
//                System.out.println("Process failed.");
//
//                BufferedReader errorReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
//                String errorLine;
//                while ((errorLine = errorReader.readLine()) != null) {
//                    System.err.println(errorLine);
//                }
//
//            }
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//        return output.toString();
//    }
//
//
//    public static void main(String[] args) {
//        String javaPath = executeCommand();
//
//        System.out.println("Java path: " + javaPath);
//    }

}
