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

	/** One thread in the browser: tasks run right away on the caller. */
	public static java.util.concurrent.ExecutorService newThreadPerTaskExecutor(java.util.concurrent.ThreadFactory factory) {
		return new InlineExecutor();
	}

	public static java.util.concurrent.ExecutorService newVirtualThreadPerTaskExecutor() {
		return new InlineExecutor();
	}

	private static final class InlineExecutor extends java.util.concurrent.AbstractExecutorService {
		private volatile boolean shut;
		@Override public void execute(Runnable r) { r.run(); }
		@Override public void shutdown() { shut = true; }
		@Override public java.util.List<Runnable> shutdownNow() { shut = true; return java.util.Collections.emptyList(); }
		@Override public boolean isShutdown() { return shut; }
		@Override public boolean isTerminated() { return shut; }
		@Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
	}

	// ---- java.net.http: mods (mostly update checkers) build an HttpClient at startup. The browser
	// runtime has none, so hand them one whose requests simply fail like an offline network.

	public static java.net.http.HttpClient newHttpClient() {
		return new OfflineHttpClient();
	}

	public static java.net.http.HttpClient.Builder newHttpClientBuilder() {
		return new OfflineHttpBuilder();
	}

	private static java.io.IOException offline() {
		return new java.io.IOException("HTTP requests from mods are not available in the browser");
	}

	private static final class OfflineHttpBuilder implements java.net.http.HttpClient.Builder {
		@Override public java.net.http.HttpClient.Builder cookieHandler(java.net.CookieHandler h) { return this; }
		@Override public java.net.http.HttpClient.Builder connectTimeout(java.time.Duration d) { return this; }
		@Override public java.net.http.HttpClient.Builder sslContext(javax.net.ssl.SSLContext c) { return this; }
		@Override public java.net.http.HttpClient.Builder sslParameters(javax.net.ssl.SSLParameters p) { return this; }
		@Override public java.net.http.HttpClient.Builder executor(Executor e) { return this; }
		@Override public java.net.http.HttpClient.Builder followRedirects(java.net.http.HttpClient.Redirect r) { return this; }
		@Override public java.net.http.HttpClient.Builder version(java.net.http.HttpClient.Version v) { return this; }
		@Override public java.net.http.HttpClient.Builder priority(int p) { return this; }
		@Override public java.net.http.HttpClient.Builder proxy(java.net.ProxySelector p) { return this; }
		@Override public java.net.http.HttpClient.Builder authenticator(java.net.Authenticator a) { return this; }
		@Override public java.net.http.HttpClient build() { return new OfflineHttpClient(); }
	}

	private static final class OfflineHttpClient extends java.net.http.HttpClient {
		@Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
		@Override public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
		@Override public Redirect followRedirects() { return Redirect.NEVER; }
		@Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
		@Override public javax.net.ssl.SSLContext sslContext() { return null; }
		@Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
		@Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
		@Override public Version version() { return Version.HTTP_1_1; }
		@Override public java.util.Optional<Executor> executor() { return java.util.Optional.empty(); }
		@Override public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest r, java.net.http.HttpResponse.BodyHandler<T> h) throws java.io.IOException {
			throw offline();
		}
		@Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest r, java.net.http.HttpResponse.BodyHandler<T> h) {
			java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> f = new java.util.concurrent.CompletableFuture<>();
			f.completeExceptionally(offline());
			return f;
		}
		@Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest r, java.net.http.HttpResponse.BodyHandler<T> h, java.net.http.HttpResponse.PushPromiseHandler<T> p) {
			return sendAsync(r, h);
		}
	}

	/** The browser file system reports a folder that already exists as an error; Java does not. */
	public static java.nio.file.Path createDirectories(java.nio.file.Path dir, java.nio.file.attribute.FileAttribute<?>... attrs) throws java.io.IOException {
		if (dir == null) return null;
		try {
			if (java.nio.file.Files.isDirectory(dir)) return dir;
		} catch (RuntimeException e) {
			// fall through and try to create it
		}
		java.nio.file.Path parent = dir.toAbsolutePath().getParent();
		if (parent != null && !parent.equals(dir.toAbsolutePath())) {
			try {
				if (!java.nio.file.Files.isDirectory(parent)) createDirectories(parent);
			} catch (RuntimeException e) {
				// keep going
			}
		}
		try {
			java.nio.file.Files.createDirectory(dir);
		} catch (java.nio.file.FileAlreadyExistsException e) {
			// already there
		}
		return dir;
	}
}
