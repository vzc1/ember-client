package net.fabricmc.loader.impl.util;

import java.util.ResourceBundle;

/**
 * Browser replacement for Fabric Loader's Localization: the real one loads a
 * ResourceBundle, which the browser runtime does not provide. Messages are
 * only used for loader errors, so the key plus its arguments is enough.
 */
public final class Localization {
	public static final ResourceBundle BUNDLE = null;
	public static final ResourceBundle ROOT_LOCALE_BUNDLE = null;

	public Localization() { }

	public static String format(String key, Object... args) {
		return formatRoot(key, args);
	}

	public static String formatRoot(String key, Object... args) {
		if (args == null || args.length == 0) return key;
		StringBuilder b = new StringBuilder(key);
		for (Object a : args) b.append(' ').append(a);
		return b.toString();
	}
}
