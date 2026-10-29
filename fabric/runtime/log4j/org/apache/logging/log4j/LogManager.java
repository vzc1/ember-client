package org.apache.logging.log4j;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.spi.LoggerContext;
import org.apache.logging.log4j.spi.LoggerContextFactory;

/**
 * Ember's browser stand-in for Log4j's LogManager. Mods get loggers that write
 * to the game's own log; there is no Log4j configuration or plugin lookup.
 */
public final class LogManager {
	public static final String FACTORY_PROPERTY_NAME = "log4j2.loggerContextFactory";
	public static final String ROOT_LOGGER_NAME = "";
	private static final Map<String, Logger> LOGGERS = new HashMap<>();

	private LogManager() { }

	public static synchronized Logger getLogger(String name) {
		String n = name == null || name.isEmpty() ? "Mod" : name;
		return LOGGERS.computeIfAbsent(n, EmberLog4jLogger::new);
	}
	public static Logger getLogger() { return getLogger("Mod"); }
	public static Logger getLogger(Class<?> c) { return getLogger(c == null ? null : c.getName()); }
	public static Logger getLogger(Class<?> c, MessageFactory f) { return getLogger(c); }
	public static Logger getLogger(MessageFactory f) { return getLogger(); }
	public static Logger getLogger(Object o) { return getLogger(o == null ? null : o.getClass().getName()); }
	public static Logger getLogger(Object o, MessageFactory f) { return getLogger(o); }
	public static Logger getLogger(String name, MessageFactory f) { return getLogger(name); }
	public static Logger getFormatterLogger() { return getLogger(); }
	public static Logger getFormatterLogger(Class<?> c) { return getLogger(c); }
	public static Logger getFormatterLogger(Object o) { return getLogger(o); }
	public static Logger getFormatterLogger(String name) { return getLogger(name); }
	public static Logger getRootLogger() { return getLogger(ROOT_LOGGER_NAME); }
	public static synchronized boolean exists(String name) { return LOGGERS.containsKey(name); }

	public static LoggerContext getContext() { return null; }
	public static LoggerContext getContext(boolean current) { return null; }
	public static LoggerContext getContext(ClassLoader l, boolean current) { return null; }
	public static LoggerContext getContext(ClassLoader l, boolean current, Object ext) { return null; }
	public static LoggerContext getContext(ClassLoader l, boolean current, URI uri) { return null; }
	public static LoggerContext getContext(ClassLoader l, boolean current, Object ext, URI uri) { return null; }
	public static LoggerContext getContext(ClassLoader l, boolean current, Object ext, URI uri, String name) { return null; }
	public static void shutdown() { }
	public static void shutdown(boolean current) { }
	public static void shutdown(boolean current, boolean all) { }
	public static void shutdown(LoggerContext context) { }
	public static LoggerContextFactory getFactory() { return null; }
	public static void setFactory(LoggerContextFactory f) { }
}
