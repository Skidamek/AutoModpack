package pl.skidam.automodpack.client.ui.versioned;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.network.chat.Component;

/** The one toast entry point; the toast manager accessor is renamed on every other version. */
public final class VersionedToasts {
	private static final List<Shown> shown = new ArrayList<>();

	private VersionedToasts() {}

	public static void add(Toast toast, Component title, Component description) {
		synchronized (shown) {
			shown.add(new Shown(title == null ? "" : title.getString(), description == null ? "" : description.getString()));
		}
		add(toast);
	}

	public static List<Shown> shown() {
		synchronized (shown) {
			return List.copyOf(shown);
		}
	}

	public record Shown(String title, String description) {}

	public static void add(Toast toast) {
		/*? if <1.21.3 {*/
		/*Minecraft.getInstance().getToasts().addToast(toast);
		*//*?} elif <26.2 {*/
		/*Minecraft.getInstance().getToastManager().addToast(toast);
		*//*?} else {*/
		Minecraft.getInstance().gui.toastManager().addToast(toast);
		/*?}*/
	}
}
