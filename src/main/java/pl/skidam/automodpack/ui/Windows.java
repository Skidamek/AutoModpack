/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.ui;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static pl.skidam.automodpack.GlobalVariables.*;

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
            InputStream inputStream = Windows.class.getClassLoader().getResourceAsStream("assets/automodpack/icon.png");
            if (inputStream == null) {
                inputStream = Windows.class.getClassLoader().getResourceAsStream("icon.png");
            }
            icon = ImageIO.read(Objects.requireNonNull(inputStream));
            inputStream.close();
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

        synchronized (Windows.class) {
            try {
                Windows.class.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}