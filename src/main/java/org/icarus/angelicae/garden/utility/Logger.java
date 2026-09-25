package org.icarus.angelicae.garden.utility;

import java.util.logging.Level;

public class Logger {

    /**
     * Name the messages are filed under, and shown with in the console. A constant rather than the plugin
     * name on purpose: this class is also used from static initialisers, which can run before the plugin
     * instance exists.
     */
    private static final String LOGGER_NAME = "GardenAngelicae";

    public static void log(String message) {
        logger().log(Level.INFO, prefix("Info", message));
    }

    public static void logErr(String message) {
        logger().log(Level.SEVERE, prefix("Error", message));
    }

    public static void logErr(Throwable throwable) {
        logger().log(Level.SEVERE, prefix("Error", throwable.toString()), throwable);
    }

    public static void logWarn(String message) {
        logger().log(Level.WARNING, prefix("Warning", message));
    }

    private static String prefix(String level, String message) {
        StackTraceElement caller = Thread.currentThread().getStackTrace()[3];
        String className = caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1);
        return "[Garden " + level + " - " + className + ":" + caller.getLineNumber() + "] " + message;
    }

    private static java.util.logging.Logger logger() {
        return java.util.logging.Logger.getLogger(LOGGER_NAME);
    }
}
