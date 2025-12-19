package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.utils.PlatformUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

public class Gui {

    private String text;
    private Semaphore semaphore;

    // After the window is closed, the jvm should exit without forgetting about eventual callbacks from ReLauncher
    public Semaphore open(String text) {
        this.text = text;
        semaphore = new Semaphore(0, true);
        if (hasAwtSupport()) {
            CompletableFuture.runAsync(this::window).whenCompleteAsync((o, throwable) -> semaphore.release());
        } else {
            CompletableFuture.runAsync(this::openForked).whenCompleteAsync((o, throwable) -> semaphore.release());
        }

        return semaphore;
    }

    private void window() {
        Semaphore windowSemaphore = new Semaphore(0, true);
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setLayout(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 150);
        frame.setResizable(false);
        Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(dim.width / 2 - frame.getSize().width / 2, dim.height / 2 - frame.getSize().height / 2);
        frame.getContentPane().setBackground(new ColorUIResource(22, 27, 34));

        JLabel RestartText = new JLabel("Restart your game!");
        RestartText.setBounds(0, 10, 400, 32);
        RestartText.setFont(new Font("Segoe UI", Font.PLAIN, 24));
        RestartText.setForeground(Color.green);
        RestartText.setHorizontalAlignment(JLabel.CENTER);

        JLabel CustomText = new JLabel(text);
        CustomText.setBounds(0, 48, 400, 36);
        CustomText.setFont(new Font("Segoe UI", Font.PLAIN, 18));
        CustomText.setForeground(Color.white);
        CustomText.setHorizontalAlignment(JLabel.CENTER);

        JButton OKButton = new JButton("OK");
        OKButton.setBounds(160, 100, 60, 25);
        OKButton.setBackground(new Color(0, 153, 51));
        OKButton.setForeground(Color.white);
        OKButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        OKButton.setFocusPainted(false);
        OKButton.addActionListener(e -> {
            frame.dispose();
            windowSemaphore.release();
        });

        BufferedImage icon = null;
        try {
            InputStream inputStream = Gui.class.getClassLoader().getResourceAsStream("icon.png");
            if (inputStream != null) {
                icon = ImageIO.read(inputStream);
                inputStream.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        frame.add(OKButton);
        frame.add(CustomText);
        frame.add(RestartText);
        frame.setTitle("AutoModpack Window");
        frame.setIconImage(icon);
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);

        if (semaphore != null) {
            semaphore.release();
        }

        try {
            windowSemaphore.acquire(); // wait for user interaction to close the gui
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Thanks to fabric loader project for this piece code - https://github.com/FabricMC/fabric-loader/blob/master/src/main/java/net/fabricmc/loader/impl/gui/FabricGuiEntry.java
    private void openForked() {
        try {
            Path javaBinDir = Paths.get(System.getProperty("java.home"), "bin").toAbsolutePath();
            String[] executables = {"javaw.exe", "java.exe", "java"};
            Path javaPath = null;

            for (String executable : executables) {
                Path path = javaBinDir.resolve(executable);

                if (Files.isRegularFile(path)) {
                    javaPath = path;
                    break;
                }
            }

            if (javaPath == null) throw new RuntimeException("can't find java executable in " + javaBinDir);

            Process process = new ProcessBuilder(javaPath.toString(), "-Xmx100M", "-cp", GlobalVariables.THIS_MOD_JAR.toString(), Gui.class.getName(), "--AM.text=" + text)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();

            final Thread shutdownHook = new Thread(process::destroy);

            Runtime.getRuntime().addShutdownHook(shutdownHook);
            int rVal = process.waitFor();
            Runtime.getRuntime().removeShutdownHook(shutdownHook);

            if (semaphore != null) {
                semaphore.release();
            }

            if (rVal != 0) throw new IOException("subprocess exited with code " + rVal);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        String text = "Failed to load text";
        for (String arg : args) {
            if (arg.startsWith("--AM.text=")) {
                text = arg.substring(10);
            }
        }
        Gui gui = new Gui();
        gui.text = text;
        gui.window();
        System.exit(0);
    }

    private static boolean hasAwtSupport() {
        if (PlatformUtils.IS_MAC) {
            // check for JAVA_STARTED_ON_FIRST_THREAD_<pid> which is set if -XstartOnFirstThread is used
            // -XstartOnFirstThread is incompatible with AWT (force enables embedded mode)
            for (String key : System.getenv().keySet()) {
                if (key.startsWith("JAVA_STARTED_ON_FIRST_THREAD_")) return false;
            }
        }

        return true;
    }
}
