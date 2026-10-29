package net.fabricmc.loader.impl.game.minecraft;

import java.io.File;

import net.emberclient.fabric.EmberFabric;

/**
 * Browser replacement for Fabric Loader's Hooks. Fabric's own game patch
 * (applied at bake time) inserts calls to these methods into Minecraft's
 * startup; here they run the baked entrypoints instead of real Fabric.
 */
public final class Hooks {
	public static final String INTERNAL_NAME = "net/fabricmc/loader/impl/game/minecraft/Hooks";
	public static String appletMainClass;
	public static final String FABRIC = "fabric";
	public static final String VANILLA = "vanilla";

	public Hooks() { }

	public static String insertBranding(String brand) {
		if (brand == null || brand.isEmpty()) return FABRIC;
		if (brand.equals(VANILLA)) return FABRIC;
		return brand.contains(FABRIC) ? brand : brand + "," + FABRIC;
	}

	public static void startClient(File runDir, Object gameInstance) {
		EmberFabric.setGameInstance(gameInstance);
		EmberFabric.startClient();
	}

	public static void startServer(File runDir, Object gameInstance) {
		EmberFabric.setGameInstance(gameInstance);
		EmberFabric.startServer();
	}

	public static void setGameInstance(Object gameInstance) {
		EmberFabric.setGameInstance(gameInstance);
	}
}
