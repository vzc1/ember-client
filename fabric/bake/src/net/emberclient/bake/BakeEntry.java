package net.emberclient.bake;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.Config;

/**
 * Build-time only. Runs inside real Fabric Loader (Knot) on a desktop JVM,
 * before the game starts, and writes out:
 *
 *   classes/   every game + mod class, with Fabric's patches, access wideners
 *              and all mixins already applied
 *   resources/ every mod's non-class files (assets, data, lang ...)
 *   gen/       EmberGenerated.java: the mod list and entrypoints as plain Java,
 *              so the browser build needs no reflection or class loading
 *   bake-report.txt
 *
 * Then it exits, so the game itself never runs here.
 */
public final class BakeEntry implements PreLaunchEntrypoint {
	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String SYNTHETIC_PREFIX = "org/spongepowered/asm/synthetic/";

	private final StringBuilder report = new StringBuilder();
	private final List<String> problems = new ArrayList<>();

	@Override
	public void onPreLaunch() {
		int code = 0;
		try {
			run();
		} catch (Throwable t) {
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			System.err.println("[ember-bake] FAILED\n" + sw);
			code = 3;
		}
		System.out.flush();
		System.err.flush();
		Runtime.getRuntime().halt(code);
	}

	private void run() throws Exception {
		Path out = Paths.get(req("ember.bake.out"));
		Path gameClasses = Paths.get(req("ember.bake.gameClasses"));
		Path classesOut = out.resolve("classes");
		Path resourcesOut = out.resolve("resources");
		Files.createDirectories(classesOut);
		Files.createDirectories(resourcesOut);

		FabricLauncher launcher = FabricLauncherBase.getLauncher();
		Set<String> synthetic = new LinkedHashSet<>();

		// 1. Game classes, fully transformed.
		List<Path> gameFiles;
		try (Stream<Path> s = Files.walk(gameClasses)) {
			gameFiles = s.filter(p -> p.toString().endsWith(".class")).sorted().toList();
		}
		int changed = 0, failed = 0;
		for (Path f : gameFiles) {
			String rel = gameClasses.relativize(f).toString().replace('\\', '/');
			String name = rel.substring(0, rel.length() - 6).replace('/', '.');
			byte[] raw = Files.readAllBytes(f);
			byte[] b = transform(launcher, name, raw);
			if (b == null) { failed++; b = raw; }
			else if (!Arrays.equals(raw, b)) changed++;
			collectSynthetic(b, synthetic);
			write(classesOut.resolve(rel), b);
		}
		log("game classes: " + gameFiles.size() + " total, " + changed + " changed by Fabric/mixins, " + failed + " failed");

		// 2. Mods: classes (transformed unless they are mixin classes) + resources.
		List<ModContainer> mods = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			ModMetadata m = mod.getMetadata();
			if ("builtin".equals(m.getType()) || "ember_bake".equals(m.getId())) continue;
			mods.add(mod);
		}
		mods.sort(Comparator.comparing(c -> c.getMetadata().getId()));

		Map<String, String> modJson = new LinkedHashMap<>();
		for (ModContainer mod : mods) {
			String id = mod.getMetadata().getId();
			int classes = 0, mixins = 0, resources = 0;
			for (Path root : mod.getRootPaths()) {
				List<Path> files;
				try (Stream<Path> s = Files.walk(root)) {
					files = s.filter(Files::isRegularFile).sorted().toList();
				}
				for (Path f : files) {
					String rel = root.relativize(f).toString().replace('\\', '/');
					if (rel.startsWith("/")) rel = rel.substring(1);
					if (rel.endsWith(".class")) {
						byte[] raw = Files.readAllBytes(f);
						ClassKind kind = kindOf(raw);
						if (kind == ClassKind.MIXIN) { mixins++; continue; }
						byte[] b = raw;
						if (kind == ClassKind.NORMAL) {
							byte[] t = transform(launcher, rel.substring(0, rel.length() - 6).replace('/', '.'), raw);
							if (t != null) b = t;
						}
						collectSynthetic(b, synthetic);
						write(classesOut.resolve(rel), b);
						classes++;
					} else if (rel.equals("fabric.mod.json")) {
						modJson.put(id, Files.readString(f, StandardCharsets.UTF_8));
					} else if (!rel.startsWith("META-INF/") && !rel.endsWith(".mixins.json")
							&& !rel.endsWith("-refmap.json") && !rel.endsWith(".accesswidener")
							&& !rel.endsWith(".classtweaker")) {
						Path dest = resourcesOut.resolve(rel);
						if (!Files.exists(dest)) { write(dest, Files.readAllBytes(f)); resources++; }
					}
				}
			}
			log("mod " + id + " " + mod.getMetadata().getVersion().getFriendlyString()
					+ ": " + classes + " classes, " + mixins + " mixin classes (applied, not shipped), " + resources + " resources");
		}

		// 3. Classes Mixin generates on demand (e.g. @ModifyArgs argument bundles).
		for (String internal : new ArrayList<>(synthetic)) {
			String name = internal.replace('/', '.');
			byte[] b = transform(launcher, name, null);
			if (b == null) problems.add("could not generate synthetic class " + name);
			else write(classesOut.resolve(internal + ".class"), b);
		}
		if (!synthetic.isEmpty()) log("synthetic mixin classes: " + synthetic.size());

		// 4. Mixin coverage: every mixin target should have received its mixins.
		for (Config config : Mixins.getConfigs()) {
			IMixinConfig c = config.getConfig();
			for (String target : c.getTargets()) {
				ClassInfo info = ClassInfo.forName(target);
				if (info == null || info.getAppliedMixins().isEmpty()) {
					problems.add("mixin config " + c.getName() + ": nothing applied to " + target);
				}
			}
		}

		// 5. Entrypoints and mod list as plain Java.
		Path gen = out.resolve("gen/net/emberclient/fabric/EmberGenerated.java");
		write(gen, generate(mods, modJson).getBytes(StandardCharsets.UTF_8));

		log("problems: " + problems.size());
		for (String p : problems) log("  ! " + p);
		write(out.resolve("bake-report.txt"), report.toString().getBytes(StandardCharsets.UTF_8));
		System.out.println("[ember-bake] done -> " + out);
	}

	private byte[] transform(FabricLauncher launcher, String name, byte[] fallback) {
		try {
			byte[] b = launcher.getClassByteArray(name, true);
			return b != null ? b : fallback;
		} catch (Throwable t) {
			problems.add(name + ": " + t);
			return null;
		}
	}

	private enum ClassKind { NORMAL, MIXIN, MIXIN_INTERFACE }

	/** Mixin classes are merged into their targets; only accessor/invoker interfaces ship. */
	private static ClassKind kindOf(byte[] b) {
		ClassReader r = new ClassReader(b);
		boolean iface = (r.getAccess() & Opcodes.ACC_INTERFACE) != 0;
		boolean[] mixin = {false};
		r.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
				if (MIXIN_DESC.equals(desc)) mixin[0] = true;
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		if (!mixin[0]) return ClassKind.NORMAL;
		return iface ? ClassKind.MIXIN_INTERFACE : ClassKind.MIXIN;
	}

	private static void collectSynthetic(byte[] b, Set<String> into) {
		String s = new String(b, StandardCharsets.ISO_8859_1);
		int i = 0;
		while ((i = s.indexOf(SYNTHETIC_PREFIX, i)) >= 0) {
			int j = i;
			while (j < s.length()) {
				char c = s.charAt(j);
				if (Character.isLetterOrDigit(c) || c == '/' || c == '$' || c == '_') j++;
				else break;
			}
			String n = s.substring(i, j);
			if (n.length() > SYNTHETIC_PREFIX.length() && !n.endsWith("/")) into.add(n);
			i = j;
		}
	}

	// ---- code generation ---------------------------------------------------

	private String generate(List<ModContainer> mods, Map<String, String> modJson) {
		List<String[]> entries = new ArrayList<>(); // key, modId, javaExpr
		for (ModContainer mod : mods) {
			if (!(mod.getMetadata() instanceof LoaderModMetadata lm)) continue;
			String id = lm.getId();
			for (String key : new TreeMap<String, Object>(toMap(lm.getEntrypointKeys())).keySet()) {
				for (EntrypointMetadata e : lm.getEntrypoints(key)) {
					if (!"default".equals(e.getAdapter())) {
						problems.add(id + ": entrypoint '" + key + "' uses language adapter '" + e.getAdapter() + "' (not supported yet)");
						continue;
					}
					String expr = javaExpr(id, key, e.getValue());
					if (expr != null) entries.add(new String[] {key, id, expr});
				}
			}
		}

		StringBuilder j = new StringBuilder();
		j.append("package net.emberclient.fabric;\n\n");
		j.append("/** Generated by the Ember bake step. Do not edit. */\n");
		j.append("public final class EmberGenerated {\n");
		j.append("\tprivate EmberGenerated() { }\n\n");
		j.append("\tpublic static final String[] MOD_IDS = {");
		for (String id : modJson.keySet()) j.append(lit(id)).append(", ");
		j.append("};\n\n");
		j.append("\tpublic static String modJson(int i) {\n\t\tswitch (i) {\n");
		int i = 0;
		for (String json : modJson.values()) j.append("\t\t\tcase ").append(i++).append(": return ").append(lit(json)).append(";\n");
		j.append("\t\t\tdefault: return null;\n\t\t}\n\t}\n\n");
		j.append("\tpublic static final String[] ENTRY_KEYS = {");
		for (String[] e : entries) j.append(lit(e[0])).append(", ");
		j.append("};\n");
		j.append("\tpublic static final String[] ENTRY_MODS = {");
		for (String[] e : entries) j.append(lit(e[1])).append(", ");
		j.append("};\n\n");
		j.append("\tpublic static Object createEntry(int i) {\n\t\tswitch (i) {\n");
		for (int k = 0; k < entries.size(); k++) j.append("\t\t\tcase ").append(k).append(": return ").append(entries.get(k)[2]).append(";\n");
		j.append("\t\t\tdefault: throw new IllegalArgumentException(String.valueOf(i));\n\t\t}\n\t}\n}\n");
		log("entrypoints: " + entries.size());
		for (String[] e : entries) log("  " + e[0] + " <- " + e[1] + ": " + e[2]);
		return j.toString();
	}

	private static Map<String, Object> toMap(Collection<String> keys) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (String k : keys) m.put(k, null);
		return m;
	}

	/** "a.b.C" -> new a.b.C(); "a.b.C::FIELD" -> a.b.C.FIELD (static field). */
	private String javaExpr(String modId, String key, String value) {
		String cls = value, member = null;
		int sep = value.indexOf("::");
		if (sep >= 0) { cls = value.substring(0, sep); member = value.substring(sep + 2); }
		String src = cls.replace('$', '.');
		if (member == null) return "new " + src + "()";
		try {
			Class<?> c = Class.forName(cls, false, FabricLauncherBase.getLauncher().getTargetClassLoader());
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (f.getName().equals(member) && java.lang.reflect.Modifier.isStatic(f.getModifiers())) return src + "." + member;
			}
		} catch (Throwable t) {
			// fall through
		}
		problems.add(modId + ": entrypoint '" + key + "' = " + value + " (method references not supported yet)");
		return null;
	}

	private static String lit(String s) {
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
					else b.append(c);
				}
			}
		}
		return b.append('"').toString();
	}

	// ---- helpers -------------------------------------------------------------

	private void log(String s) {
		System.out.println("[ember-bake] " + s);
		report.append(s).append('\n');
	}

	private static String req(String key) {
		String v = System.getProperty(key);
		if (v == null || v.isEmpty()) throw new IllegalStateException("missing -D" + key);
		return v;
	}

	private static void write(Path p, byte[] b) throws IOException {
		Files.createDirectories(p.getParent());
		Files.write(p, b);
	}
}
