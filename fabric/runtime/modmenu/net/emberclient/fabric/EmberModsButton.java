package net.emberclient.fabric;

import java.util.List;

import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Adds a "Mods" button to Eaglercraft's title screen. Mod Menu's own title
 * screen hook doesn't fit Eaglercraft's layout, so Ember adds the button itself:
 * a full-width row where Options/Credits were, with those moved down one row.
 */
final class EmberModsButton {
	private EmberModsButton() { }

	static void register() {
		if (EmberFabric.isDisabled("modmenu")) return;
		ScreenEvents.AFTER_INIT.register(EmberModsButton::afterInit);
	}

	private static void afterInit(Minecraft client, Screen screen, int width, int height) {
		if (!(screen instanceof TitleScreen)) return;
		try {
			List<AbstractWidget> widgets = Screens.getWidgets(screen);
			int rowY = -1, left = width / 2 - 100, rowWidth = 200;
			for (AbstractWidget w : widgets) {
				String text = w.getMessage().getString();
				if (text.startsWith("Options")) {
					rowY = w.getY();
					left = w.getX();
				}
			}
			if (rowY < 0) return;
			// Span the Options/Credits row exactly.
			int right = left;
			for (AbstractWidget w : widgets) {
				if (w.getY() == rowY) right = Math.max(right, w.getX() + w.getWidth());
			}
			if (right > left) rowWidth = right - left;
			for (AbstractWidget w : widgets) {
				if (w.getY() >= rowY) w.setY(w.getY() + 24);
			}
			Button mods = Button.builder(Component.literal("Mods"),
					b -> client.setScreenAndShow(ModMenuApi.createModsScreen(screen)))
					.bounds(left, rowY, rowWidth, 20).build();
			widgets.add(mods);
		} catch (Throwable t) {
			EmberFabric.log("could not add the Mods button: " + t);
		}
	}
}
