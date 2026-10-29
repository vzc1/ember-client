package net.emberclient.fabric;

import java.util.Iterator;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Small stand-ins for Java library methods the browser runtime lacks. The bake
 * rewrites calls in mod code to these (see BakeEntry.POLYFILLS).
 */
public final class EmberCompat {
	private EmberCompat() { }

	public static String getPackageName(Class<?> c) {
		while (c.isArray()) c = c.getComponentType();
		if (c.isPrimitive()) return "java.lang";
		String n = c.getName();
		int dot = n.lastIndexOf('.');
		return dot < 0 ? "" : n.substring(0, dot);
	}

	public static Class<?> componentType(Class<?> c) {
		return c.getComponentType();
	}

	public static <T> Stream<T> streamOfNullable(T t) {
		return t == null ? Stream.empty() : Stream.of(t);
	}

	public static <S> Optional<S> serviceFindFirst(ServiceLoader<S> loader) {
		Iterator<S> it = loader.iterator();
		return it.hasNext() ? Optional.of(it.next()) : Optional.empty();
	}

	/** There is one thread in the browser: "later" means "now". */
	public static Executor delayedExecutor(long delay, TimeUnit unit) {
		return Runnable::run;
	}

	public static Executor delayedExecutor(long delay, TimeUnit unit, Executor executor) {
		return executor;
	}
}
