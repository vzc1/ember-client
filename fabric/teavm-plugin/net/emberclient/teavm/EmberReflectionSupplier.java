package net.emberclient.teavm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.teavm.classlib.ReflectionContext;
import org.teavm.classlib.ReflectionSupplier;
import org.teavm.model.MethodDescriptor;

/**
 * Runs inside the TeaVM compiler, not the game. Tells it which mod classes,
 * fields and constructors must stay reachable by reflection (config loading,
 * Class.forName). The list is written by the Ember bake step.
 */
public final class EmberReflectionSupplier implements ReflectionSupplier {
	private static final String LIST = "/META-INF/ember/reflection.tsv";
	private static final Set<String> CLASSES = new HashSet<>();
	private static final Map<String, List<String>> FIELDS = new HashMap<>();
	private static final Map<String, List<MethodDescriptor>> METHODS = new HashMap<>();

	static {
		try (InputStream in = EmberReflectionSupplier.class.getResourceAsStream(LIST)) {
			if (in != null) {
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				String line;
				while ((line = r.readLine()) != null) {
					String[] p = line.split("\t", 3);
					if (p.length < 2) continue;
					switch (p[0]) {
						case "C" -> CLASSES.add(p[1]);
						case "F" -> { if (p.length == 3) FIELDS.computeIfAbsent(p[1], k -> new ArrayList<>()).add(p[2]); }
						case "M" -> {
							if (p.length == 3) {
								try {
									METHODS.computeIfAbsent(p[1], k -> new ArrayList<>()).add(MethodDescriptor.parse(p[2]));
								} catch (RuntimeException e) {
									// skip a descriptor TeaVM can't read
								}
							}
						}
						default -> { }
					}
				}
				System.out.println("[ember] reflection list: " + CLASSES.size() + " classes, "
						+ FIELDS.values().stream().mapToInt(List::size).sum() + " fields");
			}
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	@Override
	public Collection<String> getAccessibleFields(ReflectionContext context, String className) {
		return FIELDS.getOrDefault(className, Collections.emptyList());
	}

	@Override
	public Collection<MethodDescriptor> getAccessibleMethods(ReflectionContext context, String className) {
		return METHODS.getOrDefault(className, Collections.emptyList());
	}

	@Override
	@SuppressWarnings("deprecation")
	public Collection<String> getClassesFoundByName(ReflectionContext context) {
		return CLASSES;
	}

	@Override
	public boolean isClassFoundByName(ReflectionContext context, String className) {
		return CLASSES.contains(className);
	}
}
