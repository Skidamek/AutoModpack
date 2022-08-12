package pl.skidam.automodpack.ui;

import pl.skidam.automodpack.utils.Wait;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;

import java.awt.*;

public class ScreenBox extends JFrame {
    public ScreenBox(String text) {
        JFrame frame = new JFrame();
        frame.setUndecorated(true); // vanishing the title bar
        frame.setLayout(null);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(350, 120);
        frame.setResizable(false);
        frame.getContentPane().setBackground(new ColorUIResource(22, 27, 34));
        Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(dim.width / 2 - frame.getSize().width / 2, dim.height / 2 - frame.getSize().height / 2);

        JLabel RestartText = new JLabel("Restart your game!");
        RestartText.setBounds(40, 10, 260, 30);
        RestartText.setFont(new Font("Segoe UI", Font.PLAIN, 22));
        RestartText.setForeground(Color.green);

        JLabel CustomText = new JLabel(text);
        CustomText.setBounds(40, 45, 260, 18);
        CustomText.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        CustomText.setForeground(Color.white);

        JButton OKButton = new JButton("OK");
        OKButton.setBounds(140, 75, 60, 25);
        OKButton.setFocusPainted(false);
        OKButton.addActionListener(e -> {
            frame.dispose();
            System.exit(0);
        });

        ImageIcon icon = new ImageIcon("./AutoModpack/icon.png");

        frame.add(OKButton);
        frame.add(CustomText);
        frame.add(RestartText);
        frame.setTitle("AutoModpack - ScreenBox");
        frame.setIconImage(icon.getImage());
        frame.setVisible(true);

        new Wait(999999999); // 999999999 ~ 11,5 days, try to wait XD
    }
}