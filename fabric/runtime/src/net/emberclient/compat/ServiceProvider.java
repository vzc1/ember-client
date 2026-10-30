package net.emberclient.compat;

/** Stand-in for java.util.ServiceLoader.Provider, which the browser runtime lacks. */
public interface ServiceProvider<S> extends java.util.function.Supplier<S> {
	Class<? extends S> type();

	@Override
	S get();
}
