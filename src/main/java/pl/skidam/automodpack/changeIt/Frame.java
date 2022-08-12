import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.plaf.ColorUIResource;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class Frame {
    public Frame() {
        JFrame frame = new JFrame();
        frame.setLayout(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(350, 150);
        frame.setResizable(false);
        frame.getContentPane().setBackground(new ColorUIResource(13, 17, 23));
        Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(dim.width / 2 - frame.getSize().width / 2, dim.height / 2 - frame.getSize().height / 2);

        JLabel FAPIInstalled = new JLabel("Succesfully installed latest Fabric API (FAPI)!");
        FAPIInstalled.setBounds(40, 30, 260, 10);

        JButton OKButton = new JButton("OK");
        OKButton.setBounds(140, 75, 60, 25);
        OKButton.setFocusPainted(false);
        OKButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e)
            {
               frame.dispose();
            }
        });

        ImageIcon icon = new ImageIcon("./src/main/resources/assets/automodpack/icon.png");
        BufferedImage tThumbImage = new BufferedImage( 35, 35, BufferedImage.TYPE_INT_RGB );
        Graphics2D tGraphics2D = tThumbImage.createGraphics();
        tGraphics2D.setBackground( Color.WHITE );
        tGraphics2D.setPaint( Color.WHITE );
        tGraphics2D.fillRect( 0, 0, 35, 35 );
        tGraphics2D.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR );
        tGraphics2D.drawImage(icon.getImage(), 0, 0, 35, 35, null );

        try {
            ImageIO.write( tThumbImage, "png", new File("./src/images/resizedIcon.PNG"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ImageIcon resizedIcon = new ImageIcon("./src/images/resizedIcon.PNG");
        JLabel iconLabel = new JLabel(resizedIcon);
        iconLabel.setBounds(0, 0, 35, 35);
        
        frame.add(iconLabel);
        frame.add(OKButton);
        frame.add(FAPIInstalled);
        frame.setVisible(true);
    }
}
