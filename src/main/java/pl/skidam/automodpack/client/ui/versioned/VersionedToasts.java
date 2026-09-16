package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.Toast;

/** The one toast entry point; the toast manager accessor is renamed on every other version. */
public final class VersionedToasts {
	private VersionedToasts() {}

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
