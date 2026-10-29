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
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

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
	private final Map<String, List<String>> mixinTargets = new TreeMap<>(); // mixin class -> targets
	private final Map<String, String> mixinOwner = new java.util.HashMap<>();   // mixin class -> mod id
	private IMixinTransformer mixin;

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
		mixin = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
		if (mixin == null) throw new IllegalStateException("no active Mixin transformer");
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

		// Pre-pass: find every mixin class first, so mixin packages are known up front.
		for (ModContainer mod : mods) {
			String owner = mod.getMetadata().getId();
			for (Path root : mod.getRootPaths()) {
				try (Stream<Path> s = Files.walk(root)) {
					for (Path f : s.filter(p -> p.toString().endsWith(".class")).toList()) {
						Map<String, List<String>> one = new java.util.HashMap<>();
						if (kindOf(Files.readAllBytes(f), one) != ClassKind.NORMAL) {
							mixinTargets.putAll(one);
							for (String m : one.keySet()) mixinOwner.put(m, owner);
						}
					}
				}
			}
		}

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
					if (rel.endsWith("package-info.class") || rel.equals("module-info.class")) continue;
					if (rel.endsWith(".class")) {
						byte[] raw = Files.readAllBytes(f);
						ClassKind kind = kindOf(raw, mixinTargets);
						if (kind == ClassKind.MIXIN) { mixins++; continue; }
						byte[] b = raw;
						if (kind == ClassKind.NORMAL) {
							String cn = rel.substring(0, rel.length() - 6).replace('/', '.');
							if (inMixinPackage(cn)) { mixins++; continue; } // mixin helper classes: resolved below if referenced
							byte[] t = transform(launcher, cn, raw);
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

		// 2b. Mixin targets outside the game and mods (Brigadier, DataFixerUpper, ...):
		//     transform them from the classpath and ship them so they override the library copy.
		int libs = 0;
		Set<String> allTargets = new java.util.TreeSet<>();
		for (List<String> t : mixinTargets.values()) allTargets.addAll(t);
		ClassLoader targetCl = launcher.getTargetClassLoader();
		for (String target : allTargets) {
			String internal = target.replace('.', '/');
			if (Files.exists(classesOut.resolve(internal + ".class"))) continue;
			byte[] raw;
			try (java.io.InputStream in = targetCl.getResourceAsStream(internal + ".class")) {
				if (in == null) continue;
				raw = in.readAllBytes();
			}
			byte[] b = transform(launcher, target, raw);
			if (b != null && !Arrays.equals(raw, b)) { write(classesOut.resolve(internal + ".class"), b); libs++; }
		}
		log("library classes changed by mixins: " + libs);

		// 3. Classes the baked code references but nobody shipped yet: classes Mixin
		//    generates on demand (@ModifyArgs bundles, mixin inner classes) and helper
		//    classes that live in mixin packages.
		resolveMissing(launcher, classesOut);

		// 4. Mixin coverage: every merged member carries @MixinMerged(mixin = "...").
		Set<String> applied = new java.util.TreeSet<>();
		try (Stream<Path> st = Files.walk(classesOut)) {
			for (Path f : st.filter(p -> p.toString().endsWith(".class")).toList()) collectMerged(Files.readAllBytes(f), applied);
		}
		int missingMixins = 0;
		for (String m : mixinTargets.keySet()) {
			if (!applied.contains(m)) { missingMixins++; problems.add("mixin not applied: " + m + " -> " + mixinTargets.get(m)); }
		}
		log("mixins applied: " + (mixinTargets.size() - missingMixins) + " of " + mixinTargets.size()
				+ " (unapplied ones are usually server-only or target code Eaglercraft replaced)");

		// 4b. Let players switch mods off: every @Inject handler a mod's mixin added
		//     starts with "if this mod is switched off, return".
		int guarded = 0;
		try (Stream<Path> st = Files.walk(classesOut)) {
			for (Path f : st.filter(p -> p.toString().endsWith(".class")).toList()) {
				byte[] in = Files.readAllBytes(f);
				int[] n = {0};
				byte[] outBytes = guardHandlers(in, n);
				if (outBytes != null && n[0] > 0) { Files.write(f, outBytes); guarded += n[0]; }
			}
		}
		log("mixin handlers that can be switched off per mod: " + guarded);

		// 5. Entrypoints and mod list as plain Java.
		Path gen = out.resolve("gen/net/emberclient/fabric/EmberGenerated.java");
		write(gen, generate(mods, modJson).getBytes(StandardCharsets.UTF_8));

		writeFailedMixins(out);
		log("problems: " + problems.size());
		for (String p : problems) log("  ! " + p);
		write(out.resolve("bake-report.txt"), report.toString().getBytes(StandardCharsets.UTF_8));
		System.out.println("[ember-bake] done -> " + out);
	}

	private byte[] transform(FabricLauncher launcher, String name, byte[] fallback) {
		try {
			// Knot returns bytes with Fabric's own patches and access wideners, but before
			// mixins (it normally applies those while defining the class). Do that step here.
			byte[] pre = launcher.getClassByteArray(name, true);
			if (pre == null) pre = fallback;
			if (pre == null) return null;
			return mixin.transformClassBytes(name, name, pre);
		} catch (Throwable t) {
			problems.add(name + ": " + describe(t));
			return null;
		}
	}

	private static String describe(Throwable t) {
		StringBuilder b = new StringBuilder(t.toString());
		Throwable c = t.getCause();
		int depth = 0;
		while (c != null && depth++ < 6) {
			b.append("\n      caused by ").append(c);
			c = c.getCause();
		}
		return b.toString();
	}

	private enum ClassKind { NORMAL, MIXIN, MIXIN_INTERFACE }

	/** Mixin classes are merged into their targets; only accessor/invoker interfaces ship. */
	private static ClassKind kindOf(byte[] b, Map<String, List<String>> targetsOut) {
		ClassReader r = new ClassReader(b);
		boolean iface = (r.getAccess() & Opcodes.ACC_INTERFACE) != 0;
		List<String> targets = new ArrayList<>();
		boolean[] mixin = {false};
		r.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
				if (!MIXIN_DESC.equals(desc)) return null;
				mixin[0] = true;
				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitArray(String name) {
						return new AnnotationVisitor(Opcodes.ASM9) {
							@Override
							public void visit(String n, Object v) {
								if (v instanceof Type t) targets.add(t.getClassName());
								else if (v instanceof String s && "targets".equals(name)) targets.add(s.replace('/', '.'));
							}
						};
					}
				};
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		if (!mixin[0]) return ClassKind.NORMAL;
		targetsOut.put(r.getClassName().replace('/', '.'), targets);
		return iface ? ClassKind.MIXIN_INTERFACE : ClassKind.MIXIN;
	}

	private static final String MERGED_DESC = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

	private static void collectMerged(byte[] b, Set<String> into) {
		new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int v, int a, String n, String sig, String sup, String[] ifaces) {
				// interface mixins show up as an extra interface on the target
				if (ifaces != null) for (String i : ifaces) into.add(i.replace('/', '.'));
			}
			private AnnotationVisitor merged(String desc) {
				if (!MERGED_DESC.equals(desc)) return null;
				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override public void visit(String name, Object value) {
						if ("mixin".equals(name) && value instanceof String s) into.add(s.replace('/', '.'));
					}
				};
			}
			@Override
			public org.objectweb.asm.MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
				return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
					@Override public AnnotationVisitor visitAnnotation(String desc, boolean v) { return merged(desc); }
				};
			}
			@Override
			public org.objectweb.asm.FieldVisitor visitField(int a, String n, String d, String s, Object v) {
				return new org.objectweb.asm.FieldVisitor(Opcodes.ASM9) {
					@Override public AnnotationVisitor visitAnnotation(String desc, boolean vis) { return merged(desc); }
				};
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
	}

	private final Set<String> mixinPackages = new LinkedHashSet<>();

	private boolean inMixinPackage(String className) {
		if (mixinPackages.isEmpty() && !mixinTargets.isEmpty()) {
			for (String m : mixinTargets.keySet()) {
				int dot = m.lastIndexOf('.');
				if (dot > 0) mixinPackages.add(m.substring(0, dot + 1));
			}
		}
		for (String p : mixinPackages) if (className.startsWith(p)) return true;
		return false;
	}

	/** Finds classes referenced by shipped code that are not shipped and not on the classpath, and asks Mixin for them. */
	private void resolveMissing(FabricLauncher launcher, Path classesOut) throws IOException {
		ClassLoader cl = launcher.getTargetClassLoader();
		Set<String> tried = new java.util.HashSet<>();
		int generated = 0;
		for (int round = 0; round < 8; round++) {
			Set<String> refs = new java.util.TreeSet<>();
			try (Stream<Path> st = Files.walk(classesOut)) {
				for (Path f : st.filter(p -> p.toString().endsWith(".class")).toList()) collectRefs(Files.readAllBytes(f), refs);
			}
			int found = 0;
			for (String internal : refs) {
				if (!tried.add(internal)) continue;
				if (Files.exists(classesOut.resolve(internal + ".class"))) continue;
				if (internal.startsWith("java/") || internal.startsWith("javax/") || internal.startsWith("jdk/") || internal.startsWith("sun/")) continue;
				if (IGNORED_REFS.stream().anyMatch(internal::startsWith)) continue;
				String name = internal.replace('/', '.');
				byte[] b = localRefImpl(internal);
				if (b == null) {
					try { b = mixin.generateClass(MixinEnvironment.getCurrentEnvironment(), name); } catch (Throwable t) { /* not generated */ }
				}
				if (b == null && inMixinPackage(name)) {
					try (java.io.InputStream in = cl.getResourceAsStream(internal + ".class")) {
						if (in != null) b = in.readAllBytes(); // helper class inside a mixin package: ship as-is
					}
				}
				if (b != null) { write(classesOut.resolve(internal + ".class"), b); generated++; found++; }
				else if (cl.getResource(internal + ".class") == null && ClassLoader.getSystemResource(internal + ".class") == null) {
					problems.add("referenced class not found anywhere: " + name);
				}
			}
			if (found == 0) break;
		}
		log("classes generated or recovered for references: " + generated);
	}

	/** Never used in the browser, or supplied by the browser build itself. */
	private static final List<String> IGNORED_REFS = List.of(
			"org/teavm/", "org/jetbrains/annotations/", "net/minecraft/launchwrapper/",
			"org/spongepowered/asm/launch/", "cpw/mods/", "org/apache/logging/log4j/");

	private static final String LOCAL_REF_GEN = "com/llamalad7/mixinextras/sugar/impl/ref/generated/";
	private static final Map<String, String> LOCAL_REF_TYPES = Map.of(
			"LocalBooleanRef", "Z", "LocalByteRef", "B", "LocalCharRef", "C", "LocalDoubleRef", "D",
			"LocalFloatRef", "F", "LocalIntRef", "I", "LocalLongRef", "J", "LocalShortRef", "S",
			"LocalRef", "Ljava/lang/Object;");

	/** MixinExtras defines its LocalRef implementations at runtime; build the same bytes here. */
	private byte[] localRefImpl(String internal) {
		if (!internal.startsWith(LOCAL_REF_GEN) || !internal.endsWith("Impl")) return null;
		String iface = internal.substring(LOCAL_REF_GEN.length(), internal.length() - 4);
		String desc = LOCAL_REF_TYPES.get(iface);
		if (desc == null) return null;
		try {
			ClassLoader cl = FabricLauncherBase.getLauncher().getTargetClassLoader();
			Class<?> gen = Class.forName("com.llamalad7.mixinextras.sugar.impl.ref.LocalRefClassGenerator", true, cl);
			java.lang.reflect.Method m = gen.getDeclaredMethod("generateClass",
					org.objectweb.asm.tree.ClassNode.class, String.class, String.class, String.class);
			m.setAccessible(true);
			org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
			node.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, internal, null, "java/lang/Object", null);
			m.invoke(null, node, internal, desc, "com.llamalad7.mixinextras.sugar.ref." + iface);
			org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES) {
				@Override protected ClassLoader getClassLoader() { return cl; }
			};
			node.accept(w);
			return w.toByteArray();
		} catch (Throwable t) {
			problems.add("could not build " + internal + ": " + describe(t));
			return null;
		}
	}

	/** "config.json:pkg.MixinName" for every mixin that failed to apply, for the next bake to switch off. */
	private void writeFailedMixins(Path out) throws IOException {
		java.util.regex.Pattern[] patterns = {
			java.util.regex.Pattern.compile("Mixin \\[([^\\]\\s:]+\\.json):(\\S+) from mod"),
			java.util.regex.Pattern.compile(" in ([^\\s:]+\\.json):(\\S+) from mod"),
		};
		Set<String> failed = new java.util.TreeSet<>();
		for (String p : problems) {
			for (java.util.regex.Pattern pat : patterns) {
				java.util.regex.Matcher m = pat.matcher(p);
				while (m.find()) failed.add(m.group(1) + ":" + m.group(2));
			}
		}
		Files.write(out.resolve("failed-mixins.txt"), failed);
		if (!failed.isEmpty()) log("mixins that failed to apply (switched off on the next bake): " + failed);
	}

	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	/** Adds a switch-off check to the start of void @Inject handlers merged from mod mixins. */
	private byte[] guardHandlers(byte[] b, int[] count) {
		try {
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			new ClassReader(b).accept(cn, 0);
			if ((cn.access & Opcodes.ACC_INTERFACE) != 0) return null;
			for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
				if (mn.instructions == null || mn.instructions.size() == 0) continue;
				if (!mn.desc.endsWith(")V") || !(mn.desc.contains(CI) || mn.desc.contains(CIR))) continue;
				String owner = mergedOwner(mn);
				if (owner == null) continue;
				String mod = mixinOwner.get(owner);
				if (mod == null || mod.startsWith("fabric-") || mod.equals("fabric-api")) continue; // keep the library on
				org.objectweb.asm.tree.AbstractInsnNode first = mn.instructions.getFirst();
				while (first != null && first.getOpcode() < 0 && !(first instanceof org.objectweb.asm.tree.FrameNode)) first = first.getNext();
				if (first instanceof org.objectweb.asm.tree.FrameNode) continue; // already a jump target at the start; leave as is
				org.objectweb.asm.tree.LabelNode go = new org.objectweb.asm.tree.LabelNode();
				org.objectweb.asm.tree.InsnList guard = new org.objectweb.asm.tree.InsnList();
				guard.add(new org.objectweb.asm.tree.LdcInsnNode(mod));
				guard.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, "net/emberclient/fabric/EmberFabric", "isDisabled", "(Ljava/lang/String;)Z", false));
				guard.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, go));
				guard.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
				guard.add(go);
				guard.add(new org.objectweb.asm.tree.FrameNode(Opcodes.F_SAME, 0, null, 0, null));
				mn.instructions.insert(guard);
				mn.maxStack = Math.max(mn.maxStack, 1);
				count[0]++;
			}
			if (count[0] == 0) return null;
			org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(0);
			cn.accept(w);
			return w.toByteArray();
		} catch (Throwable t) {
			problems.add("could not add switch-off checks: " + describe(t));
			return null;
		}
	}

	private static String mergedOwner(org.objectweb.asm.tree.MethodNode mn) {
		List<org.objectweb.asm.tree.AnnotationNode> all = new ArrayList<>();
		if (mn.invisibleAnnotations != null) all.addAll(mn.invisibleAnnotations);
		if (mn.visibleAnnotations != null) all.addAll(mn.visibleAnnotations);
		for (org.objectweb.asm.tree.AnnotationNode a : all) {
			if (!MERGED_DESC.equals(a.desc) || a.values == null) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				if ("mixin".equals(a.values.get(i))) return String.valueOf(a.values.get(i + 1)).replace('/', '.');
			}
		}
		return null;
	}

	private static void collectRefs(byte[] b, Set<String> into) {
		ClassReader r = new ClassReader(b);
		char[] buf = new char[r.getMaxStringLength()];
		for (int i = 1; i < r.getItemCount(); i++) {
			int off = r.getItem(i);
			if (off == 0) continue;
			if (r.readByte(off - 1) == 7) { // CONSTANT_Class
				String n = r.readUTF8(off, buf);
				while (n.startsWith("[")) n = n.substring(1);
				if (n.startsWith("L") && n.endsWith(";")) n = n.substring(1, n.length() - 1);
				if (n.length() > 1) into.add(n);
			}
		}
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

	private static final Map<String, String> STANDARD = Map.of(
			"main", "net.fabricmc.api.ModInitializer",
			"client", "net.fabricmc.api.ClientModInitializer",
			"server", "net.fabricmc.api.DedicatedServerModInitializer",
			"preLaunch", "net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint");

	/**
	 * "a.b.C"          -> new a.b.C()
	 * "a.b.C::FIELD"   -> a.b.C.FIELD                       (static field)
	 * "a.b.C::method"  -> (Iface) a.b.C::method            (static method, standard keys)
	 *                  -> (Iface) new a.b.C()::method      (instance method, standard keys)
	 */
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
			String iface = STANDARD.get(key);
			if (iface != null) {
				for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
					if (!m.getName().equals(member) || m.getParameterCount() != 0) continue;
					boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
					return "((" + iface + ") " + (isStatic ? src : "new " + src + "()") + "::" + member + ")";
				}
			}
		} catch (Throwable t) {
			problems.add(modId + ": could not inspect entrypoint " + value + ": " + t);
			return null;
		}
		problems.add(modId + ": entrypoint '" + key + "' = " + value + " (method reference on a custom key, not supported yet)");
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
