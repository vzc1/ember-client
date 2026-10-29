package net.emberclient.fabric;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.platform.NativeImage;
import com.terraformersmc.modmenu.util.mod.fabric.FabricIconHandler;

import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Mod Menu loads icons straight out of mod jar files; in the browser there are no
 * jars, so icons come from the game's assets instead (the bake puts each mod's
 * icon at assets/ember_icons/<mod id>.png).
 */
public final class EmberModIcons {
	private EmberModIcons() { }

	private static final Map<String, DynamicTexture> CACHE = new HashMap<>();

	public static DynamicTexture createIcon(FabricIconHandler handler, ModContainer container, String path) {
		String key = container.getMetadata().getId() + "|" + path;
		if (CACHE.containsKey(key)) return CACHE.get(key);
		DynamicTexture tex = null;
		try {
			Identifier id;
			if (path.startsWith("assets/") && path.indexOf('/', 7) > 0) {
				int slash = path.indexOf('/', 7);
				id = Identifier.fromNamespaceAndPath(path.substring(7, slash), path.substring(slash + 1));
			} else {
				id = Identifier.fromNamespaceAndPath("ember_icons", container.getMetadata().getId().toLowerCase(Locale.ROOT) + ".png");
			}
			Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(id);
			if (res.isPresent()) {
				try (InputStream in = res.get().open()) {
					NativeImage img = NativeImage.read(in);
					tex = new DynamicTexture(() -> "ember mod icon " + key, img);
				}
			}
		} catch (Throwable t) {
			EmberFabric.log("no icon for " + key + ": " + t);
		}
		CACHE.put(key, tex);
		return tex;
	}
}
