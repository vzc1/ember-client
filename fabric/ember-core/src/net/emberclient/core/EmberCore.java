package net.emberclient.core;

import net.fabricmc.api.ClientModInitializer;

public final class EmberCore implements ClientModInitializer {
	public static boolean initialized;

	@Override
	public void onInitializeClient() {
		initialized = true;
		System.out.println("[Ember] Fabric client entrypoint ran");
	}
}
