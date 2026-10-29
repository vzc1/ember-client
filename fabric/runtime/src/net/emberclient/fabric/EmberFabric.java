package net.emberclient.fabric;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

/**
 * Ember's in-browser stand-in for Fabric Loader's runtime. Everything it knows
 * (mods, entrypoints) was decided at bake time and compiled into EmberGenerated,
 * so nothing here scans files or loads classes.
 */
public final class EmberFabric {
	private EmberFabric() { }

	private static final String[][] BUILTIN = {
		{"minecraft", "26.2", "Minecraft"},
		{"java", "25", "Java"},
		{"fabricloader", "0.20.1", "Fabric Loader"},
	};

	/** Mods the player switched off on the Ember site (one Fabric mod id per line, saved by the site). */
	private static java.util.Set<String> disabled;

	public static boolean isDisabled(String modId) {
		if (disabled == null) {
			java.util.Set<String> set = new java.util.HashSet<>();
			try {
				byte[] b = net.lax1dude.eaglercraft.v1_8.internal.PlatformApplication.getLocalStorage("ember_disabled_mods", false);
				if (b != null) {
					for (String line : new String(b, StandardCharsets.UTF_8).split("\n")) {
						line = line.trim();
						if (!line.isEmpty()) set.add(line);
					}
				}
			} catch (Throwable t) {
				log("could not read the switched-off mods list: " + t);
			}
			disabled = set;
			if (!set.isEmpty()) log("switched off: " + set);
		}
		return disabled.contains(modId);
	}

	private static Map<String, EmberModContainer> byId;
	private static List<ModContainer> mods;
	private static Object[] instances;
	private static Object gameInstance;
	private static boolean clientStarted, serverStarted;
	private static final ObjectShare SHARE = new Share();
	private static final MappingResolver MAPPINGS = new IdentityMappings();

	private static synchronized void init() {
		if (byId != null) return;
		Map<String, EmberModContainer> ids = new LinkedHashMap<>();
		List<ModContainer> all = new ArrayList<>();
		VersionOverrides versions = new VersionOverrides();
		DependencyOverrides deps = new DependencyOverrides(configDir());
		for (String[] b : BUILTIN) {
			String json = "{\"schemaVersion\":1,\"id\":\"" + b[0] + "\",\"version\":\"" + b[1] + "\",\"name\":\"" + b[2] + "\"}";
			add(parse(json, b[0], versions, deps), "builtin", ids, all);
		}
		for (int i = 0; i < EmberGenerated.MOD_IDS.length; i++) {
			LoaderModMetadata m = parse(EmberGenerated.modJson(i), EmberGenerated.MOD_IDS[i], versions, deps);
			if (m != null) add(m, null, ids, all);
		}
		byId = ids;
		mods = Collections.unmodifiableList(all);
		instances = new Object[EmberGenerated.ENTRY_KEYS.length];
		log(all.size() + " mods: " + ids.keySet());
	}

	private static LoaderModMetadata parse(String json, String id, VersionOverrides v, DependencyOverrides d) {
		try {
			return ModMetadataParser.parseMetadata(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
					"ember:" + id, Collections.emptyList(), v, d, false);
		} catch (Throwable t) {
			log("could not read fabric.mod.json of " + id + ": " + t);
			return null;
		}
	}

	private static void add(LoaderModMetadata m, String type, Map<String, EmberModContainer> ids, List<ModContainer> all) {
		if (m == null) return;
		EmberModContainer c = new EmberModContainer(m, "builtin".equals(type));
		all.add(c);
		ids.putIfAbsent(m.getId(), c);
		for (String alias : m.getProvides()) ids.putIfAbsent(alias, c);
	}

	// ---- loader API ---------------------------------------------------------

	public static Collection<ModContainer> mods() { init(); return mods; }

	public static Optional<ModContainer> mod(String id) { init(); return Optional.ofNullable(byId.get(id)); }

	@SuppressWarnings("unchecked")
	public static <T> List<EntrypointContainer<T>> entrypoints(String key, Class<T> type) {
		init();
		List<EntrypointContainer<T>> out = new ArrayList<>();
		String[] keys = EmberGenerated.ENTRY_KEYS;
		for (int i = 0; i < keys.length; i++) {
			if (!keys[i].equals(key)) continue;
			if (isDisabled(EmberGenerated.ENTRY_MODS[i])) continue;
			Object inst = instance(i);
			if (inst == null) continue;
			if (!type.isInstance(inst)) {
				log("entrypoint '" + key + "' of " + EmberGenerated.ENTRY_MODS[i] + " is not a " + type.getName());
				continue;
			}
			out.add(new Entry<>((T) inst, byId.get(EmberGenerated.ENTRY_MODS[i])));
		}
		return out;
	}

	public static <T> void invoke(String key, Class<T> type, Consumer<? super T> invoker) {
		for (EntrypointContainer<T> c : entrypoints(key, type)) {
			try {
				invoker.accept(c.getEntrypoint());
			} catch (Throwable t) {
				log("entrypoint '" + key + "' of " + c.getProvider().getMetadata().getId() + " failed: " + t);
				t.printStackTrace();
			}
		}
	}

	private static Object instance(int i) {
		if (instances[i] == null) {
			try {
				instances[i] = EmberGenerated.createEntry(i);
			} catch (Throwable t) {
				log("could not create entrypoint " + EmberGenerated.ENTRY_KEYS[i] + " of " + EmberGenerated.ENTRY_MODS[i] + ": " + t);
				t.printStackTrace();
			}
		}
		return instances[i];
	}

	// ---- game lifecycle (called from Hooks) ------------------------------------

	public static void startClient() {
		if (clientStarted) return;
		clientStarted = true;
		init();
		log("starting client entrypoints");
		invoke("main", ModInitializer.class, ModInitializer::onInitialize);
		invoke("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
	}

	public static void startServer() {
		if (serverStarted) return;
		serverStarted = true;
		init();
		invoke("main", ModInitializer.class, ModInitializer::onInitialize);
	}

	public static void setGameInstance(Object instance) { gameInstance = instance; }
	public static Object gameInstance() { return gameInstance; }
	public static Path gameDir() { return Paths.get("."); }
	public static Path configDir() { return Paths.get("config"); }
	public static ObjectShare objectShare() { return SHARE; }
	public static MappingResolver mappingResolver() { return MAPPINGS; }

	static void log(String s) { System.out.println("[Ember/Fabric] " + s); }

	// ---- small helpers -----------------------------------------------------

	private record Entry<T>(T entrypoint, ModContainer provider) implements EntrypointContainer<T> {
		@Override public T getEntrypoint() { return entrypoint; }
		@Override public ModContainer getProvider() { return provider; }
	}

	private static final class Share implements ObjectShare {
		private final Map<String, Object> values = new HashMap<>();
		private final Map<String, List<BiConsumer<String, Object>>> waiting = new HashMap<>();

		@Override public synchronized Object get(String key) { return values.get(key); }

		@Override
		public synchronized void whenAvailable(String key, BiConsumer<String, Object> consumer) {
			Object v = values.get(key);
			if (v != null) consumer.accept(key, v);
			else waiting.computeIfAbsent(key, k -> new ArrayList<>()).add(consumer);
		}

		@Override
		public synchronized Object put(String key, Object value) {
			Object old = values.put(key, value);
			List<BiConsumer<String, Object>> w = waiting.remove(key);
			if (w != null) for (BiConsumer<String, Object> c : w) c.accept(key, value);
			return old;
		}

		@Override
		public synchronized Object putIfAbsent(String key, Object value) {
			Object old = values.get(key);
			return old != null ? old : put(key, value);
		}

		@Override public synchronized Object remove(String key) { return values.remove(key); }
	}

	/** 26.x ships unobfuscated, so every namespace maps to the same names. */
	private static final class IdentityMappings implements MappingResolver {
		@Override public Collection<String> getNamespaces() { return List.of("official", "named"); }
		@Override public String getCurrentRuntimeNamespace() { return "official"; }
		@Override public String mapClassName(String ns, String name) { return name; }
		@Override public String unmapClassName(String ns, String name) { return name; }
		@Override public String mapFieldName(String ns, String owner, String name, String desc) { return name; }
		@Override public String mapMethodName(String ns, String owner, String name, String desc) { return name; }
	}
}
