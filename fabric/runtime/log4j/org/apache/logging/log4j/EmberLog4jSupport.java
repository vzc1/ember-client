package org.apache.logging.log4j;

import java.util.ArrayList;
import java.util.List;

/** Turns any Log4j call's arguments into one line for the game's logger. */
final class EmberLog4jSupport {
	private EmberLog4jSupport() { }

	static boolean on(org.slf4j.Logger out, String level) {
		switch (level) {
			case "trace": return out.isTraceEnabled();
			case "debug": return out.isDebugEnabled();
			case "info": return out.isInfoEnabled();
			case "warn": return out.isWarnEnabled();
			default: return out.isErrorEnabled();
		}
	}

	static void emit(org.slf4j.Logger out, String level, Object... raw) {
		try {
			List<Object> args = new ArrayList<>();
			for (Object o : raw) {
				if (o instanceof Object[] arr) { for (Object x : arr) args.add(x); } else args.add(o);
			}
			if ("log".equals(level)) {
				level = "info";
				for (Object o : args) {
					if (o instanceof Level l) { level = l.name().toLowerCase(java.util.Locale.ROOT); break; }
				}
			}
			args.removeIf(o -> o instanceof Level || o instanceof Marker);
			Throwable thrown = null;
			if (!args.isEmpty() && args.get(args.size() - 1) instanceof Throwable t) {
				thrown = t;
				args.remove(args.size() - 1);
			}
			String msg = args.isEmpty() ? (thrown != null ? String.valueOf(thrown) : "") : text(args.remove(0));
			StringBuilder sb = new StringBuilder();
			int from = 0, i = 0;
			while (true) {
				int at = msg.indexOf("{}", from);
				if (at < 0 || i >= args.size()) { sb.append(msg, from, msg.length()); break; }
				sb.append(msg, from, at).append(text(args.get(i++)));
				from = at + 2;
			}
			if (i == args.size() - 1 && thrown == null && args.get(i) instanceof Throwable t) thrown = t;
			String line = sb.toString();
			switch (level) {
				case "trace": if (thrown != null) out.trace(line, thrown); else out.trace(line); break;
				case "debug": if (thrown != null) out.debug(line, thrown); else out.debug(line); break;
				case "info": if (thrown != null) out.info(line, thrown); else out.info(line); break;
				case "warn": if (thrown != null) out.warn(line, thrown); else out.warn(line); break;
				case "off": break;
				default: if (thrown != null) out.error(line, thrown); else out.error(line); break;
			}
		} catch (Throwable t) {
			// Logging must never take the game down.
		}
	}

	private static String text(Object o) {
		if (o instanceof org.apache.logging.log4j.message.Message m) return m.getFormattedMessage();
		if (o instanceof org.apache.logging.log4j.util.MessageSupplier s) return text(s.get());
		if (o instanceof org.apache.logging.log4j.util.Supplier<?> s) return text(s.get());
		if (o instanceof java.util.function.Supplier<?> s) return text(s.get());
		return String.valueOf(o);
	}
}
