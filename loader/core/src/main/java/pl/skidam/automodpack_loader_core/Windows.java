package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.callbacks.Callback;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

// I use arch btw
public class Windows {

    // Dont use awt on mac
    public static boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");

    public void restartWindow(String text, Callback... callbacks) {
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setLayout(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 150);
        frame.setResizable(false);
        if (!isMac) {
            Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
            frame.setLocation(dim.width / 2 - frame.getSize().width / 2, dim.height / 2 - frame.getSize().height / 2);
        }
        frame.getContentPane().setBackground(new ColorUIResource(22, 27, 34));

        JLabel RestartText = new JLabel("Restart your game!");
        RestartText.setBounds(0, 10, 400, 32);
        if (!isMac) {
            RestartText.setFont(new Font("Segoe UI", Font.PLAIN, 24));
            RestartText.setForeground(Color.green);
        }
        RestartText.setHorizontalAlignment(JLabel.CENTER); // center the text

        JLabel CustomText = new JLabel(text);
        CustomText.setBounds(0, 54, 400, 36);
        if (!isMac) {
            CustomText.setFont(new Font("Segoe UI", Font.PLAIN, 18));
            CustomText.setForeground(Color.white);
        }
        CustomText.setHorizontalAlignment(JLabel.CENTER); // center the text

        JButton OKButton = new JButton("OK");
        OKButton.setBounds(160, 100, 60, 25);
        if (!isMac) {
            OKButton.setBackground(new Color(0, 153, 51)); // set background color
            OKButton.setForeground(Color.white); // set text color
            OKButton.setFont(new Font("Segoe UI", Font.BOLD, 14)); // set font style and size
        }
        OKButton.setFocusPainted(false);
        OKButton.addActionListener(e -> {
            frame.dispose();
            System.exit(0);
        });

        BufferedImage icon = null;
        try {
            InputStream inputStream = Windows.class.getClassLoader().getResourceAsStream("icon.png");
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

        // Here, before the window is closed, to make sure its done
        for (Callback callback : callbacks) {
            try {
                callback.run();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        synchronized (Windows.class) {
            try {
                Windows.class.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}