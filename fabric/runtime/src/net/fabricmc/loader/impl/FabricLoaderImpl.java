package net.fabricmc.loader.impl;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import net.emberclient.fabric.EmberFabric;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/**
 * Browser replacement for Fabric Loader's FabricLoaderImpl, backed by the mod
 * list and entrypoints generated at bake time. FabricLoader.getInstance()
 * reads INSTANCE, so this is what every mod talks to.
 */
public final class FabricLoaderImpl implements FabricLoader {
	public static final FabricLoaderImpl INSTANCE = new FabricLoaderImpl();
	public static final int ASM_VERSION = 0x90000; // Opcodes.ASM9
	public static final String VERSION = "0.20.1+ember";
	public static final String MOD_ID = "fabricloader";
	public static final String CACHE_DIR_NAME = ".fabric";
	public static final String REMAPPED_JARS_DIR_NAME = "remappedJars";

	private FabricLoaderImpl() { }

	@Override
	public <T> List<T> getEntrypoints(String key, Class<T> type) {
		List<T> out = new ArrayList<>();
		for (EntrypointContainer<T> c : EmberFabric.entrypoints(key, type)) out.add(c.getEntrypoint());
		return out;
	}

	@Override
	public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
		return EmberFabric.entrypoints(key, type);
	}

	@Override
	public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
		EmberFabric.invoke(key, type, invoker);
	}

	@Override public ObjectShare getObjectShare() { return EmberFabric.objectShare(); }
	@Override public MappingResolver getMappingResolver() { return EmberFabric.mappingResolver(); }
	@Override public Optional<ModContainer> getModContainer(String id) { return EmberFabric.mod(id); }
	@Override public Collection<ModContainer> getAllMods() { return EmberFabric.mods(); }
	@Override public boolean isModLoaded(String id) { return EmberFabric.mod(id).isPresent(); }
	@Override public boolean isDevelopmentEnvironment() { return false; }
	@Override public EnvType getEnvironmentType() { return EnvType.CLIENT; }
	@Override public String getRawGameVersion() { return "26.2"; }
	@Override public Object getGameInstance() { return EmberFabric.gameInstance(); }
	@Override public Path getGameDir() { return EmberFabric.gameDir(); }
	@Override public File getGameDirectory() { return EmberFabric.gameDir().toFile(); }
	@Override public Path getConfigDir() { return EmberFabric.configDir(); }
	@Override public File getConfigDirectory() { return EmberFabric.configDir().toFile(); }
	@Override public String[] getLaunchArguments(boolean sanitize) { return new String[0]; }
}
