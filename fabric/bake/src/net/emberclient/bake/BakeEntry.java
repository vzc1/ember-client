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
			if (b == null) { failed++; continue; }        // the browser build keeps its own copy
			if (Arrays.equals(raw, b)) continue;           // unchanged: no need to ship it again
			changed++;
			checkBytecode(b);
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
						} else if (kind == ClassKind.MIXIN_INTERFACE) {
							// Accessor/invoker interfaces: Mixin writes their static method bodies
							// when they load; shipped raw they throw "Untransformed @Accessor".
							String cn = rel.substring(0, rel.length() - 6).replace('/', '.');
							try {
								byte[] t = mixin.transformClassBytes(cn, cn, raw);
								if (t != null && !java.util.Arrays.equals(t, raw)) { b = t; accessorsFixed++; }
							} catch (Throwable e) {
								problems.add("accessor " + cn + ": " + describe(e));
							}
						}
						collectSynthetic(b, synthetic);
						write(classesOut.resolve(rel), b);
						modClassFiles.add(rel);
						classes++;
					} else if (rel.equals("fabric.mod.json")) {
						modJson.put(id, Files.readString(f, StandardCharsets.UTF_8));
					} else if (rel.startsWith("META-INF/services/") && !rel.contains("javax.annotation")) {
						// ServiceLoader registrations: the browser compiler reads these at build time.
						Path dest = out.resolve("services").resolve(rel.substring("META-INF/services/".length()));
						Files.createDirectories(dest.getParent());
						String text = Files.readString(f, StandardCharsets.UTF_8).trim();
						if (!text.isEmpty()) Files.writeString(dest, text + "\n", StandardCharsets.UTF_8,
								java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
					} else if (!rel.startsWith("META-INF/") && !rel.endsWith(".mixins.json")
							&& !rel.endsWith("-refmap.json") && !rel.endsWith(".accesswidener")
							&& !rel.endsWith(".classtweaker")) {
						Path dest = resourcesOut.resolve(rel);
						if (!Files.exists(dest)) { write(dest, Files.readAllBytes(f)); resources++; }
					}
				}
			}
			// Mod Menu shows each mod's icon; give every icon a fixed place in the game's assets.
			try {
				String icon = mod.getMetadata().getIconPath(64).orElse(null);
				if (icon != null) {
					java.util.Optional<Path> ip = mod.findPath(icon);
					if (ip.isPresent() && Files.isRegularFile(ip.get())) {
						write(resourcesOut.resolve("assets/ember_icons/" + id.toLowerCase(java.util.Locale.ROOT) + ".png"), Files.readAllBytes(ip.get()));
					}
				}
			} catch (Throwable t) {
				problems.add("icon of " + id + ": " + describe(t));
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

		// 4b2. A few mod hooks assume vanilla's code around them; Eaglercraft's differs
		//      (e.g. its singleplayer loading screen). Make those hooks give up quietly.
		int safe = 0;
		try (Stream<Path> st = Files.walk(classesOut)) {
			for (Path f : st.filter(p -> p.toString().endsWith(".class")).toList()) {
				int[] n = {0};
				byte[] outBytes = safeHandlers(Files.readAllBytes(f), n);
				if (outBytes != null && n[0] > 0) { Files.write(f, outBytes); safe += n[0]; }
			}
		}
		log("mod hooks made failure-safe: " + safe);

		// 4c. Reroute calls to Java library methods the browser lacks to Ember's stand-ins.
		int rerouted = 0;
		try (Stream<Path> st = Files.walk(classesOut)) {
			for (Path f : st.filter(p -> p.toString().endsWith(".class")).toList()) {
				int[] n = {0};
				byte[] outBytes = polyfill(Files.readAllBytes(f), n);
				if (outBytes != null && n[0] > 0) { Files.write(f, outBytes); rerouted += n[0]; }
			}
		}
		log("library calls rerouted to browser stand-ins: " + rerouted);

		// 5. Entrypoints and mod list as plain Java.
		Path gen = out.resolve("gen/net/emberclient/fabric/EmberGenerated.java");
		write(gen, generate(mods, modJson).getBytes(StandardCharsets.UTF_8));

		writeReflection(classesOut, out.resolve("reflection.tsv"));
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
	private int accessorsFixed;
	private final Set<String> modClassFiles = new java.util.TreeSet<>();

	/**
	 * The browser build only allows reflection on what it is told about. Mods read
	 * and write their config classes by reflection (Gson, Cloth Config, Jankson) and
	 * look up their own classes by name, so list every mod class with its fields,
	 * constructors and record accessors for Ember's TeaVM ReflectionSupplier.
	 */
	private void writeReflection(Path classesOut, Path tsv) throws IOException {
		StringBuilder sb = new StringBuilder();
		int classes = 0, fields = 0, methods = 0;
		// Loader internals, Fabric API and MixinExtras don't load configs by reflection;
		// leaving them out keeps the browser build small.
		List<String> skip = List.of("net/fabricmc/", "com/llamalad7/", "org/spongepowered/", "org/objectweb/");
		// Class names that appear as text in mod code: what Class.forName might be asked for.
		Set<String> named = new java.util.HashSet<>();
		Set<String> namedTails = new java.util.HashSet<>();
		for (String rel : modClassFiles) {
			Path f = classesOut.resolve(rel);
			if (!Files.exists(f)) continue;
			try {
				new ClassReader(Files.readAllBytes(f)).accept(new ClassVisitor(Opcodes.ASM9) {
					@Override public org.objectweb.asm.FieldVisitor visitField(int a, String n, String d, String sg, Object v) {
						if (v instanceof String str && str.indexOf('.') > 0) named.add(str);
						return null;
					}
					@Override public org.objectweb.asm.MethodVisitor visitMethod(int a, String n, String d, String sg, String[] e) {
						return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
							@Override public void visitLdcInsn(Object v) { if (v instanceof String str && str.indexOf('.') > 0) named.add(str); }
							// "pkg + \".ClassName\"" string concatenation: remember the ".ClassName" tail.
							@Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle bsm, Object... args) {
								for (Object a : args) {
									if (!(a instanceof String recipe)) continue;
									for (String part : recipe.split("[\u0001\u0002]")) {
										if (part.startsWith(".") && part.length() > 1 && Character.isUpperCase(part.charAt(part.lastIndexOf('.') + 1)))
											namedTails.add(part);
									}
								}
							}
						};
					}
				}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			} catch (Throwable t) { /* ignore */ }
		}
		Map<String, org.objectweb.asm.tree.ClassNode> nodes = new java.util.HashMap<>();
		for (String rel : modClassFiles) {
			if (skip.stream().anyMatch(rel::startsWith)) continue;
			Path f = classesOut.resolve(rel);
			if (!Files.exists(f)) continue;
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			try {
				new ClassReader(Files.readAllBytes(f)).accept(cn, ClassReader.SKIP_CODE);
			} catch (Throwable t) {
				continue;
			}
			String name = cn.name.replace('/', '.');
			if (name.endsWith("package-info") || name.equals("module-info")) continue;
			// Architectury-style mods find their platform code as "<pkg>.fabric.<Name>Impl".
			boolean byName = named.contains(name) || (name.endsWith("Impl") && name.contains(".fabric."))
					|| namedTails.stream().anyMatch(name::endsWith);
			if (byName) {
				sb.append("C\t").append(name).append('\n');
				classes++;
			}
			nodes.put(cn.name, cn);
		}
		// Field/constructor reflection is expensive for the browser compiler (every
		// reflective field widens what it must track), so only config classes get it,
		// plus the mod classes they hold in their fields and their superclasses.
		java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
		Set<String> reflect = new java.util.LinkedHashSet<>();
		for (String n : nodes.keySet()) {
			String name = n.replace('/', '.');
			if (name.toLowerCase(java.util.Locale.ROOT).contains("config") && !name.contains(".gui.")
					&& !name.startsWith("me.shedaniel.") && !name.startsWith("com.electronwill.")
					&& !name.contains(".shadowed.") && !name.contains(".libs.")) todo.add(n);
		}
		while (!todo.isEmpty()) {
			String n = todo.poll();
			org.objectweb.asm.tree.ClassNode cn = nodes.get(n);
			if (cn == null || !reflect.add(n)) continue;
			if (cn.superName != null) todo.add(cn.superName);
			for (org.objectweb.asm.tree.FieldNode fn : cn.fields) {
				if ((fn.access & Opcodes.ACC_STATIC) != 0) continue;
				String sig = (fn.signature != null ? fn.signature : "") + fn.desc;
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("L([\\w/$]+)[;<]").matcher(sig);
				while (m.find()) if (nodes.containsKey(m.group(1))) todo.add(m.group(1));
			}
		}
		for (String n : reflect) {
			org.objectweb.asm.tree.ClassNode cn = nodes.get(n);
			String name = n.replace('/', '.');
			for (org.objectweb.asm.tree.FieldNode fn : cn.fields) {
				if ((fn.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
				sb.append("F\t").append(name).append('\t').append(fn.name).append('\n');
				fields++;
			}
			Set<String> accessors = new java.util.HashSet<>();
			if (cn.recordComponents != null) {
				for (org.objectweb.asm.tree.RecordComponentNode rc : cn.recordComponents) accessors.add(rc.name + "()" + rc.descriptor);
			}
			for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
				boolean ctor = mn.name.equals("<init>");
				if (!ctor && !accessors.contains(mn.name + mn.desc)) continue;
				sb.append("M\t").append(name).append('\t').append(mn.name).append(mn.desc).append('\n');
				methods++;
			}
		}
		write(tsv, sb.toString().getBytes(StandardCharsets.UTF_8));
		log("reflection allowed for mods: " + classes + " classes by name, " + fields + " fields, " + methods + " constructors/record accessors");
	}

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
					byte[] raw = null;
					try (java.io.InputStream in = cl.getResourceAsStream(internal + ".class")) {
						if (in != null) raw = in.readAllBytes();
					}
					if (raw != null) {
						// Accessor/invoker interfaces get their static method bodies from Mixin when
						// loaded; untransformed they throw "Untransformed @Accessor". Run them through
						// Mixin first, and ship plain helper classes as they are.
						byte[] t = null;
						try { t = mixin.transformClassBytes(name, name, raw); } catch (Throwable e) { /* not a loadable mixin */ }
						b = (t != null && !java.util.Arrays.equals(t, raw)) ? t : raw;
						if (b == t) accessorsFixed++;
					}
				}
				if (b != null) { write(classesOut.resolve(internal + ".class"), b); generated++; found++; }
				else if (cl.getResource(internal + ".class") == null && ClassLoader.getSystemResource(internal + ".class") == null) {
					problems.add("referenced class not found anywhere: " + name);
				}
			}
			if (found == 0) break;
		}
		log("classes generated or recovered for references: " + generated + " (accessor interfaces filled in: " + accessorsFixed + ")");
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
	/** Mixins whose merged code doesn't verify; switched off on the next bake round. */
	private final Set<String> invalidMixins = new java.util.TreeSet<>();

	/**
	 * Mixin can produce methods the JVM would reject (e.g. a captured local that isn't
	 * set on every path). The browser compiler stops on those, so find the mixin
	 * responsible and switch it off.
	 */
	private void checkBytecode(byte[] b) {
		try {
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			new ClassReader(b).accept(cn, 0);
			Map<String, String> merged = new java.util.HashMap<>();
			for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
				String owner = mergedOwner(mn);
				if (owner != null) merged.put(mn.name + mn.desc, owner);
			}
			if (merged.isEmpty()) return;
			for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
				if (mn.instructions == null || mn.instructions.size() == 0) continue;
				try {
					new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(cn.name, mn);
				} catch (org.objectweb.asm.tree.analysis.AnalyzerException e) {
					Set<String> culprits = new java.util.TreeSet<>();
					String self = merged.get(mn.name + mn.desc);
					if (self != null) culprits.add(self);
					for (org.objectweb.asm.tree.AbstractInsnNode in : mn.instructions) {
						if (in instanceof org.objectweb.asm.tree.MethodInsnNode mi && mi.owner.equals(cn.name)) {
							String o = merged.get(mi.name + mi.desc);
							if (o != null) culprits.add(o);
						}
					}
					// Fabric API is what other mods build on; when a mod's hook is also there, blame the mod.
					if (culprits.stream().anyMatch(c -> !c.startsWith("net.fabricmc."))) culprits.removeIf(c -> c.startsWith("net.fabricmc."));
					problems.add("invalid bytecode in " + cn.name + "." + mn.name + " (" + e.getMessage() + "); switching off " + culprits);
					invalidMixins.addAll(culprits);
				}
			}
		} catch (Throwable t) {
			// checking is best effort
		}
	}

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
		for (String m : invalidMixins) failed.add("*:" + m);
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
				boolean isVoidHook = mn.desc.endsWith(")V") && (mn.desc.contains(CI) || mn.desc.contains(CIR));
				// Hooks that change a value (@ModifyVariable, @ModifyArg, @ModifyReturnValue...) take the
				// value and return its replacement. If the mod is off or the hook trips, hand back the
				// original value unchanged.
				org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mn.desc);
				org.objectweb.asm.Type retType = org.objectweb.asm.Type.getReturnType(mn.desc);
				boolean isPassHook = !isVoidHook && args.length >= 1 && retType.getSort() != org.objectweb.asm.Type.VOID
						&& args[0].equals(retType);
				if (!isVoidHook && !isPassHook) continue;
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
				addReturn(guard, mn, isPassHook, retType);
				guard.add(go);
				guard.add(new org.objectweb.asm.tree.FrameNode(Opcodes.F_SAME, 0, null, 0, null));
				mn.instructions.insert(guard);
				// And if the hook throws, switch that mod off instead of crashing the game.
				org.objectweb.asm.tree.LabelNode tryStart = new org.objectweb.asm.tree.LabelNode();
				org.objectweb.asm.tree.LabelNode tryEnd = new org.objectweb.asm.tree.LabelNode();
				org.objectweb.asm.tree.LabelNode onError = new org.objectweb.asm.tree.LabelNode();
				mn.instructions.insert(tryStart);
				mn.instructions.add(tryEnd);
				mn.instructions.add(onError);
				mn.instructions.add(new org.objectweb.asm.tree.FrameNode(Opcodes.F_FULL, frameLocals(cn, mn).size(),
						frameLocals(cn, mn).toArray(), 1, new Object[] {"java/lang/Throwable"}));
				mn.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(mod));
				mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
				mn.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, "net/emberclient/fabric/EmberFabric", "hookFailed", "(Ljava/lang/String;Ljava/lang/Throwable;)V", false));
				addReturn(mn.instructions, mn, isPassHook, retType);
				mn.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(tryStart, tryEnd, onError, "java/lang/Throwable"));
				mn.maxStack = Math.max(mn.maxStack, 2);
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

	/** Return from a guarded hook: nothing for void hooks, the untouched first argument for value hooks. */
	private static void addReturn(org.objectweb.asm.tree.InsnList l, org.objectweb.asm.tree.MethodNode mn, boolean pass, org.objectweb.asm.Type ret) {
		if (!pass) { l.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN)); return; }
		int slot = (mn.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		l.add(new org.objectweb.asm.tree.VarInsnNode(ret.getOpcode(Opcodes.ILOAD), slot));
		l.add(new org.objectweb.asm.tree.InsnNode(ret.getOpcode(Opcodes.IRETURN)));
		mn.maxStack = Math.max(mn.maxStack, ret.getSize());
	}

	/** Merged mixin handlers (by name) that should catch their own errors instead of crashing the game. */
	private static final List<String> SAFE_HANDLERS = List.of(
			"$fabric-screen-api-v1$beforeLoadingScreenTick",
			"$fabric-screen-api-v1$afterLoadingScreenTick");

	/**
	 * Whole methods (class.method) that should give up quietly: saving a mod's config
	 * file. In the browser some config writers hit reflection limits; the mod then
	 * keeps its default settings instead of failing to start.
	 */
	private static final List<String> SAFE_METHODS = List.of(
			"me/shedaniel/autoconfig/ConfigManager.save",
			"com/terraformersmc/modmenu/config/ModMenuConfigManager.save",
			// Mouse Tweaks runs on every mouse event; if it trips, let the click through instead of crashing.
			"yalter/mousetweaks/Main.onMouseClicked",
			"yalter/mousetweaks/Main.onMouseReleased",
			"yalter/mousetweaks/Main.onMouseDrag",
			"yalter/mousetweaks/Main.onMouseScrolled");

	private byte[] safeHandlers(byte[] b, int[] count) {
		String s = new String(b, StandardCharsets.ISO_8859_1);
		boolean anyMethod = SAFE_METHODS.stream().anyMatch(m -> s.contains(m.substring(0, m.indexOf('.'))));
		if (SAFE_HANDLERS.stream().noneMatch(s::contains) && !anyMethod) return null;
		try {
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			new ClassReader(b).accept(cn, 0);
			for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
				boolean wanted = SAFE_HANDLERS.stream().anyMatch(mn.name::endsWith) || SAFE_METHODS.contains(cn.name + "." + mn.name);
				char ret = mn.desc.charAt(mn.desc.indexOf(')') + 1);
				if (!wanted || "VZIJFDBSCL[".indexOf(ret) < 0) continue;
				if (mn.instructions.size() == 0) continue;
				org.objectweb.asm.tree.LabelNode start = new org.objectweb.asm.tree.LabelNode();
				org.objectweb.asm.tree.LabelNode end = new org.objectweb.asm.tree.LabelNode();
				org.objectweb.asm.tree.LabelNode handler = new org.objectweb.asm.tree.LabelNode();
				mn.instructions.insert(start);
				mn.instructions.add(end);
				mn.instructions.add(handler);
				// Frame at the handler: the method's own parameters, and the caught Throwable on the stack.
				List<Object> locals = new ArrayList<>();
				if ((mn.access & Opcodes.ACC_STATIC) == 0) locals.add(cn.name);
				for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(mn.desc)) {
					switch (t.getSort()) {
						case org.objectweb.asm.Type.BOOLEAN, org.objectweb.asm.Type.BYTE, org.objectweb.asm.Type.CHAR,
								org.objectweb.asm.Type.SHORT, org.objectweb.asm.Type.INT -> locals.add(Opcodes.INTEGER);
						case org.objectweb.asm.Type.FLOAT -> locals.add(Opcodes.FLOAT);
						case org.objectweb.asm.Type.LONG -> locals.add(Opcodes.LONG);
						case org.objectweb.asm.Type.DOUBLE -> locals.add(Opcodes.DOUBLE);
						default -> locals.add(t.getInternalName());
					}
				}
				mn.instructions.add(new org.objectweb.asm.tree.FrameNode(Opcodes.F_FULL, locals.size(), locals.toArray(),
						1, new Object[] {"java/lang/Throwable"}));
				mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
				// Return the "nothing happened" value for the method's type.
				switch (ret) {
					case 'V' -> mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
					case 'J' -> { mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LCONST_0)); mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LRETURN)); }
					case 'F' -> { mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.FCONST_0)); mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.FRETURN)); }
					case 'D' -> { mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DCONST_0)); mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DRETURN)); }
					case 'L', '[' -> { mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL)); mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN)); }
					default -> { mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0)); mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN)); }
				}
				mn.maxStack = Math.max(mn.maxStack, 2);
				mn.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
				mn.maxStack = Math.max(mn.maxStack, 1);
				count[0]++;
			}
			if (count[0] == 0) return null;
			org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(0);
			cn.accept(w);
			return w.toByteArray();
		} catch (Throwable t) {
			problems.add("could not make hooks failure-safe: " + describe(t));
			return null;
		}
	}

	/** Stack-map locals for a method's parameters (used for frames at added catch handlers). */
	private static List<Object> frameLocals(org.objectweb.asm.tree.ClassNode cn, org.objectweb.asm.tree.MethodNode mn) {
		List<Object> locals = new ArrayList<>();
		if ((mn.access & Opcodes.ACC_STATIC) == 0) locals.add(cn.name);
		for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(mn.desc)) {
			switch (t.getSort()) {
				case org.objectweb.asm.Type.BOOLEAN, org.objectweb.asm.Type.BYTE, org.objectweb.asm.Type.CHAR,
						org.objectweb.asm.Type.SHORT, org.objectweb.asm.Type.INT -> locals.add(Opcodes.INTEGER);
				case org.objectweb.asm.Type.FLOAT -> locals.add(Opcodes.FLOAT);
				case org.objectweb.asm.Type.LONG -> locals.add(Opcodes.LONG);
				case org.objectweb.asm.Type.DOUBLE -> locals.add(Opcodes.DOUBLE);
				default -> locals.add(t.getInternalName());
			}
		}
		return locals;
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

	/** owner.name+desc of a missing library method -> static replacement (receiver becomes the first argument). */
	private static final Map<String, String[]> POLYFILLS = Map.of(
			"java/lang/Class.getPackageName()Ljava/lang/String;", new String[] {"getPackageName", "(Ljava/lang/Class;)Ljava/lang/String;"},
			"java/lang/Class.componentType()Ljava/lang/Class;", new String[] {"componentType", "(Ljava/lang/Class;)Ljava/lang/Class;"},
			"java/util/stream/Stream.ofNullable(Ljava/lang/Object;)Ljava/util/stream/Stream;", new String[] {"streamOfNullable", "(Ljava/lang/Object;)Ljava/util/stream/Stream;"},
			"java/util/ServiceLoader.findFirst()Ljava/util/Optional;", new String[] {"serviceFindFirst", "(Ljava/util/ServiceLoader;)Ljava/util/Optional;"},
			"java/util/concurrent/CompletableFuture.delayedExecutor(JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/Executor;", new String[] {"delayedExecutor", "(JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/Executor;"},
			"java/util/concurrent/CompletableFuture.delayedExecutor(JLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/Executor;", new String[] {"delayedExecutor", "(JLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/Executor;"},
			"java/util/concurrent/Executors.newThreadPerTaskExecutor(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;", new String[] {"newThreadPerTaskExecutor", "(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;"},
			"java/util/concurrent/Executors.newVirtualThreadPerTaskExecutor()Ljava/util/concurrent/ExecutorService;", new String[] {"newVirtualThreadPerTaskExecutor", "()Ljava/util/concurrent/ExecutorService;"},
			"java/nio/file/Files.createDirectories(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;", new String[] {"createDirectories", "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;"},
			"com/terraformersmc/modmenu/util/mod/fabric/FabricIconHandler.createIcon(Lnet/fabricmc/loader/api/ModContainer;Ljava/lang/String;)Lnet/minecraft/client/renderer/texture/DynamicTexture;", new String[] {"createIcon", "(Lcom/terraformersmc/modmenu/util/mod/fabric/FabricIconHandler;Lnet/fabricmc/loader/api/ModContainer;Ljava/lang/String;)Lnet/minecraft/client/renderer/texture/DynamicTexture;", "net/emberclient/fabric/EmberModIcons"});

	private static boolean classExists(String name) {
		String res = name.replace('.', '/') + ".class";
		if (name.startsWith("java.") || name.startsWith("javax.")) return false; // leave JDK lookups alone
		ClassLoader cl = FabricLauncherBase.getLauncher().getTargetClassLoader();
		return cl.getResource(res) != null || ClassLoader.getSystemResource(res) != null;
	}

	/** Whole Java classes the browser lacks, swapped for Ember's own versions in mod code. */
	private static final Map<String, String> CLASS_SWAPS = Map.of(
			"java/lang/ScopedValue", "net/emberclient/compat/ScopedValue",
			"java/lang/ScopedValue$Carrier", "net/emberclient/compat/ScopedValue$Carrier",
			"java/lang/ScopedValue$CallableOp", "net/emberclient/compat/ScopedValue$CallableOp");

	private static byte[] swapClasses(byte[] b) {
		String s = new String(b, StandardCharsets.ISO_8859_1);
		if (CLASS_SWAPS.keySet().stream().noneMatch(s::contains)) return null;
		org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(0);
		new ClassReader(b).accept(new org.objectweb.asm.commons.ClassRemapper(w,
				new org.objectweb.asm.commons.SimpleRemapper(Opcodes.ASM9, CLASS_SWAPS)), 0);
		return w.toByteArray();
	}

	/** Netty handler bases that pick messages by their generic type (found by reflection at runtime). */
	private static final Map<String, String> NETTY_MATCHERS = Map.of(
			"io/netty/handler/codec/MessageToMessageEncoder", "acceptOutboundMessage",
			"io/netty/handler/codec/MessageToByteEncoder", "acceptOutboundMessage",
			"io/netty/handler/codec/MessageToMessageDecoder", "acceptInboundMessage",
			"io/netty/channel/SimpleChannelInboundHandler", "acceptInboundMessage");

	/**
	 * Netty finds a handler's message type through generic-type reflection, which the
	 * browser build lacks, so the handler ends up accepting every message (Fabric's
	 * packet splitter then fails with ClassCastException). Write the check out instead.
	 */
	private static byte[] nettyMatchers(byte[] b) {
		String s = new String(b, StandardCharsets.ISO_8859_1);
		if (!s.contains("io/netty/")) return null;
		org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
		new ClassReader(b).accept(cn, 0);
		String method = NETTY_MATCHERS.get(cn.superName);
		if (method == null || cn.signature == null) return null;
		for (org.objectweb.asm.tree.MethodNode mn : cn.methods) if (mn.name.equals(method)) return null;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("L" + java.util.regex.Pattern.quote(cn.superName) + "<L([^;<]+)[;<]").matcher(cn.signature);
		if (!m.find()) return null;
		org.objectweb.asm.tree.MethodNode mn = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, method, "(Ljava/lang/Object;)Z", null, new String[] {"java/lang/Exception"});
		mn.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
		mn.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.INSTANCEOF, m.group(1)));
		mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
		mn.maxStack = 1;
		mn.maxLocals = 2;
		cn.methods.add(mn);
		org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(0);
		cn.accept(w);
		return w.toByteArray();
	}

	private byte[] polyfill(byte[] b, int[] count) {
		byte[] netty = null;
		try {
			netty = nettyMatchers(b);
		} catch (Throwable t) {
			problems.add("could not add Netty message checks: " + describe(t));
		}
		if (netty != null) { b = netty; count[0]++; }
		byte[] swapped = null;
		try {
			swapped = swapClasses(b);
		} catch (Throwable t) {
			problems.add("could not swap library classes: " + describe(t));
		}
		if (swapped != null) { b = swapped; count[0]++; }
		byte[] rest = polyfillCalls(b, count);
		return rest != null ? rest : swapped != null ? swapped : netty;
	}

	private byte[] polyfillCalls(byte[] b, int[] count) {
		try {
			String s = new String(b, StandardCharsets.ISO_8859_1);
			if (!s.contains("getPackageName") && !s.contains("componentType") && !s.contains("ofNullable")
					&& !s.contains("findFirst") && !s.contains("delayedExecutor") && !s.contains("PerTaskExecutor")
					&& !s.contains("forName") && !s.contains("createDirectories") && !s.contains("createIcon")) return null;
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			new ClassReader(b).accept(cn, 0);
			for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
				if (mn.instructions == null) continue;
				for (org.objectweb.asm.tree.AbstractInsnNode in : mn.instructions.toArray()) {
					if (!(in instanceof org.objectweb.asm.tree.MethodInsnNode m)) continue;
					// Class.forName("some.Class") only finds classes the browser build was told
					// about. When the class exists, turn it into a plain class literal so it is
					// built in; missing classes keep throwing ClassNotFoundException as before.
					if (m.getOpcode() == Opcodes.INVOKESTATIC && m.owner.equals("java/lang/Class") && m.name.equals("forName")
							&& m.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")
							&& m.getPrevious() instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof String cname
							&& !cname.startsWith("[") && classExists(cname)) {
						mn.instructions.set(ldc, new org.objectweb.asm.tree.LdcInsnNode(org.objectweb.asm.Type.getObjectType(cname.replace('.', '/'))));
						mn.instructions.remove(m);
						count[0]++;
						continue;
					}
					String[] r = POLYFILLS.get(m.owner + "." + m.name + m.desc);
					if (r == null) continue;
					mn.instructions.set(m, new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
							r.length > 2 ? r[2] : "net/emberclient/fabric/EmberCompat", r[0], r[1], false));
					count[0]++;
				}
			}
			if (count[0] == 0) return null;
			org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(0);
			cn.accept(w);
			return w.toByteArray();
		} catch (Throwable t) {
			problems.add("could not reroute library calls: " + describe(t));
			return null;
		}
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
