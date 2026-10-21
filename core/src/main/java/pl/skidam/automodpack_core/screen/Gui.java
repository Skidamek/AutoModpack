package pl.skidam.automodpack_core.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.storage.StoragePaths.GUI_LOG_FILE;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import pl.skidam.automodpack_core.client.DetachedProcesses;
import pl.skidam.automodpack_core.client.RuntimeJar;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.utils.PlatformUtils;

public class Gui {

	private String text;

	/** Spawns the restart window in its own JVM and returns immediately; falls back to this JVM only when spawning fails. */
	public void open(String text) {
		this.text = text;
		try {
			openForked();
		} catch (Exception e) {
			LOGGER.error("Could not open the restart window in a separate process", e);
			if (!hasAwtSupport()) {
				LOGGER.error("AWT is unavailable; cannot show the restart window");
				LOGGER.info("Please restart the game to apply updates!");
				return;
			}
			try {
				window();
			} catch (Exception fallbackFailure) {
				LOGGER.error("Could not show the restart window", fallbackFailure);
				LOGGER.info("Please restart the game to apply updates!");
			}
		}
	}

	private void window() {
		Semaphore windowSemaphore = new Semaphore(0, true);
		JFrame frame = new JFrame();
		frame.setUndecorated(true);
		frame.setLayout(null);
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setResizable(false);
		Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
		frame.getContentPane().setBackground(new ColorUIResource(22, 27, 34));

		JLabel RestartText = new JLabel("Restart your game!");
		RestartText.setBounds(0, 10, 400, 32);
		RestartText.setFont(new Font("Segoe UI", Font.PLAIN, 24));
		RestartText.setForeground(Color.green);
		RestartText.setHorizontalAlignment(JLabel.CENTER);

		// The deferred-update guidance runs longer than one line; Swing wraps a label only through the HTML renderer.
		JLabel CustomText = new JLabel("<html><div style='text-align:center;width:380px'>" + text + "</div></html>");
		CustomText.setVerticalAlignment(JLabel.TOP);
		CustomText.setFont(new Font("Segoe UI", Font.PLAIN, 14));
		CustomText.setForeground(Color.white);
		CustomText.setHorizontalAlignment(JLabel.CENTER);
		int textHeight = CustomText.getPreferredSize().height;
		int height = Math.max(200, Math.min(44 + textHeight + 42, dim.height - 40));
		frame.setSize(400, height);
		frame.setLocation(dim.width / 2 - frame.getSize().width / 2, dim.height / 2 - frame.getSize().height / 2);
		CustomText.setBounds(0, 44, 400, textHeight);

		JButton OKButton = new JButton("OK");
		OKButton.setBounds(170, height - 42, 60, 25);
		OKButton.setBackground(new Color(0, 153, 51));
		OKButton.setForeground(Color.white);
		OKButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
		OKButton.setFocusPainted(false);
		OKButton.addActionListener(e -> {
			frame.dispose();
			windowSemaphore.release();
		});
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				windowSemaphore.release();
			}
		});

		BufferedImage icon = null;
		try {
			InputStream inputStream = Gui.class.getClassLoader().getResourceAsStream("icon.png");
			if (inputStream != null) {
				icon = ImageIO.read(inputStream);
				inputStream.close();
			}
		} catch (IOException e) {
			LOGGER.warn("Could not load the restart window icon", e);
		}

		frame.add(OKButton);
		frame.add(CustomText);
		frame.add(RestartText);
		frame.setTitle("AutoModpack Window");
		frame.setIconImage(icon);
		frame.setAlwaysOnTop(true);
		frame.setVisible(true);

		try {
			windowSemaphore.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.error("Restart window interrupted", e);
		}
	}

	private void openForked() throws IOException {
		Path log = GameDirectory.current().resolve(GUI_LOG_FILE).toAbsolutePath().normalize();
		DetachedProcesses.start(Gui.class, List.of("-Xmx100M"), List.of("--AM.text=" + text), GameDirectory.current(), log, guiClasspath());
	}

	private static List<Path> guiClasspath() throws IOException {
		List<Path> classpath = new ArrayList<>();
		classpath.add(RuntimeJar.current());
		classpath.add(RuntimeJar.dependency(LogManager.class));
		classpath.add(RuntimeJar.dependency(LoggerContext.class));
		return classpath;
	}

	public static void main(String[] args) throws Exception {
		DetachedProcesses.ignoreHangup();
		RuntimeJar.holdLease();
		String text = "Failed to load text";
		for (String arg : args) {
			if (arg.startsWith("--AM.text=")) text = arg.substring(10);
		}
		Gui gui = new Gui();
		gui.text = text;
		gui.window();
		System.exit(0);
	}

	private static boolean hasAwtSupport() {
		if (PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.MACOS) {
			// check for JAVA_STARTED_ON_FIRST_THREAD_<pid> which is set if -XstartOnFirstThread is used
			// -XstartOnFirstThread is incompatible with AWT (force enables embedded mode)
			for (String key : System.getenv().keySet()) {
				if (key.startsWith("JAVA_STARTED_ON_FIRST_THREAD_")) return false;
			}
		}

		return true;
	}
}
