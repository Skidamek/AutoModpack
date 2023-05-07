package pl.skidam.automodpack.ui;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

import static pl.skidam.automodpack.StaticVariables.*;

// I use Linux btw
public class Windows {
    public void restartWindow(String text) {
        if (quest) {
            LOGGER.info("Quest mode is enabled, skipping restart window");
            return;
        }
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
        RestartText.setHorizontalAlignment(JLabel.CENTER); // center the text

        JLabel CustomText = new JLabel(text);
        CustomText.setBounds(0, 54, 400, 36);
        CustomText.setFont(new Font("Segoe UI", Font.PLAIN, 18));
        CustomText.setForeground(Color.white);
        CustomText.setHorizontalAlignment(JLabel.CENTER); // center the text

        JButton OKButton = new JButton("OK");
        OKButton.setBounds(160, 100, 60, 25);
        OKButton.setBackground(new Color(0, 153, 51)); // set background color
        OKButton.setForeground(Color.white); // set text color
        OKButton.setFont(new Font("Segoe UI", Font.BOLD, 14)); // set font style and size
        OKButton.setFocusPainted(false);
        OKButton.addActionListener(e -> {
            frame.dispose();
            System.exit(0);
        });

        BufferedImage icon = null;
        try {
            icon = ImageIO.read(Objects.requireNonNull(Windows.class.getClassLoader().getResourceAsStream("assets/automodpack/icon.png")));
        } catch (IOException e) {
            e.printStackTrace();
        }

        frame.add(OKButton);
        frame.add(CustomText);
        frame.add(RestartText);
        frame.setTitle("AutoModpack window");
        frame.setIconImage(icon);
        frame.setVisible(true);
        frame.requestFocus();
        frame.toFront();

        synchronized (Windows.class) {
            try {
                Windows.class.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void restartingWindow() {
        if (quest) {
            LOGGER.info("Quest mode is enabled, skipping restarting window");
            return;
        }
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setLayout(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 150);
        frame.setResizable(false);
        frame.getContentPane().setBackground(new ColorUIResource(22, 27, 34));
        Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(dim.width / 2 - frame.getSize().width / 2, dim.height / 2 - frame.getSize().height / 2);

        JLabel RestartText = new JLabel("Minecraft is restarting...");
        RestartText.setBounds(0, 10, 400, 32);
        RestartText.setFont(new Font("Segoe UI", Font.PLAIN, 24));
        RestartText.setForeground(Color.GREEN);
        RestartText.setHorizontalAlignment(JLabel.CENTER); // center the text

        JLabel CustomText = new JLabel("Don't launch Minecraft manually!");
        CustomText.setBounds(0, 54, 400, 36);
        CustomText.setFont(new Font("Segoe UI", Font.PLAIN, 18));
        CustomText.setForeground(Color.red);
        CustomText.setHorizontalAlignment(JLabel.CENTER); // center the text

        JButton OKButton = new JButton("OK");
        OKButton.setBounds(160, 100, 60, 25);
        OKButton.setBackground(new Color(0, 153, 51)); // set background color
        OKButton.setForeground(Color.white); // set text color
        OKButton.setFont(new Font("Segoe UI", Font.BOLD, 14)); // set font style and size
        OKButton.setFocusPainted(false);
        OKButton.addActionListener(e -> {
            frame.dispose();
            System.exit(0);
        });

        BufferedImage icon = null;
        try {
            icon = ImageIO.read(Objects.requireNonNull(Windows.class.getClassLoader().getResourceAsStream("assets/automodpack/icon.png")));
        } catch (IOException e) {
            e.printStackTrace();
        }

        frame.add(OKButton);
        frame.add(CustomText);
        frame.add(RestartText);
        frame.setTitle("AutoModpack window");
        frame.setIconImage(icon);
        frame.setVisible(true);
        frame.requestFocus();
        frame.toFront();

        synchronized (Windows.class) {
            try {
                Windows.class.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void errorRestartingWindow() {
        if (quest) {
            LOGGER.warn("Re-launcher don't work on Quest!");
            return;
        }
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setLayout(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 150);
        frame.setResizable(false);
        frame.getContentPane().setBackground(new ColorUIResource(22, 27, 34));
        Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(dim.width / 2 - frame.getSize().width / 2, dim.height / 2 - frame.getSize().height / 2);

        JLabel RestartText = new JLabel("Re-launcher don't work on your OS!");
        RestartText.setBounds(0, 10, 400, 32);
        RestartText.setFont(new Font("Segoe UI", Font.PLAIN, 18));
        RestartText.setForeground(Color.red);
        RestartText.setHorizontalAlignment(JLabel.CENTER); // center the text

        JLabel CustomText = new JLabel("https://github.com/Skidamek/AutoModpack/issues/87");
        CustomText.setBounds(0, 54, 400, 36);
        CustomText.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        CustomText.setForeground(Color.white);
        CustomText.setHorizontalAlignment(JLabel.CENTER); // center the text

        JButton OKButton = new JButton("OK");
        OKButton.setBounds(160, 100, 60, 25);
        OKButton.setBackground(new Color(0, 153, 51)); // set background color
        OKButton.setForeground(Color.white); // set text color
        OKButton.setFont(new Font("Segoe UI", Font.BOLD, 14)); // set font style and size
        OKButton.setFocusPainted(false);
        OKButton.addActionListener(e -> {
            frame.dispose();
            System.exit(0);
        });

        BufferedImage icon = null;
        try {
            icon = ImageIO.read(Objects.requireNonNull(Windows.class.getClassLoader().getResourceAsStream("assets/automodpack/icon.png")));
        } catch (IOException e) {
            e.printStackTrace();
        }

        frame.add(OKButton);
        frame.add(CustomText);
        frame.add(RestartText);
        frame.setTitle("AutoModpack window");
        frame.setIconImage(icon);
        frame.setVisible(true);
        frame.requestFocus();
        frame.toFront();

        synchronized (Windows.class) {
            try {
                Windows.class.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}