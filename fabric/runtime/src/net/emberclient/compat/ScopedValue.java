package net.emberclient.compat;

import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Browser stand-in for java.lang.ScopedValue (Java 25), which the browser
 * runtime lacks. The bake rewrites mod code that uses ScopedValue to use this.
 * The browser game runs on one thread, so a value bound by where(...).call(...)
 * is simply set for the duration of the call and restored afterwards.
 */
public final class ScopedValue<T> {
	private static final Object UNBOUND = new Object();
	private Object value = UNBOUND;

	private ScopedValue() { }

	public static <T> ScopedValue<T> newInstance() {
		return new ScopedValue<>();
	}

	public static <T> Carrier where(ScopedValue<T> key, T value) {
		return new Carrier(null, key, value);
	}

	@SuppressWarnings("unchecked")
	public T get() {
		if (value == UNBOUND) throw new NoSuchElementException("ScopedValue not bound");
		return (T) value;
	}

	public boolean isBound() {
		return value != UNBOUND;
	}

	@SuppressWarnings("unchecked")
	public T orElse(T other) {
		return value == UNBOUND ? other : (T) value;
	}

	@SuppressWarnings("unchecked")
	public <X extends Throwable> T orElseThrow(Supplier<? extends X> exception) throws X {
		if (value == UNBOUND) throw exception.get();
		return (T) value;
	}

	@FunctionalInterface
	public interface CallableOp<T, X extends Throwable> {
		T call() throws X;
	}

	public static final class Carrier {
		private final Carrier prev;
		private final ScopedValue<?> key;
		private final Object value;

		Carrier(Carrier prev, ScopedValue<?> key, Object value) {
			this.prev = prev;
			this.key = key;
			this.value = value;
		}

		public <T> Carrier where(ScopedValue<T> key, T value) {
			return new Carrier(this, key, value);
		}

		@SuppressWarnings("unchecked")
		public <T> T get(ScopedValue<T> key) {
			for (Carrier c = this; c != null; c = c.prev) if (c.key == key) return (T) c.value;
			throw new NoSuchElementException("key not bound in this carrier");
		}

		public <R, X extends Throwable> R call(CallableOp<? extends R, X> op) throws X {
			int n = 0;
			for (Carrier c = this; c != null; c = c.prev) n++;
			ScopedValue<?>[] keys = new ScopedValue<?>[n];
			Object[] old = new Object[n];
			Object[] vals = new Object[n];
			int i = 0;
			for (Carrier c = this; c != null; c = c.prev, i++) {
				keys[i] = c.key;
				vals[i] = c.value;
				old[i] = c.key.value;
			}
			// Oldest binding first, so a later where(...) for the same key wins.
			for (int j = n - 1; j >= 0; j--) keys[j].value = vals[j];
			try {
				return op.call();
			} finally {
				for (int j = n - 1; j >= 0; j--) keys[j].value = old[j];
			}
		}

		public void run(Runnable op) {
			call(() -> { op.run(); return null; });
		}
	}
}
