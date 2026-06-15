package com.typui.database.util;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class StartupBanner {
    
    private static final String VERSION = "2.1.0";
    private static final String BUILD_DATE = "2026-04-25";
    
    public static void print() {
        System.out.println();
        printBanner();
        printVersion();
        printSystemInfo();
        printDivider();
    }
    
    private static void printBanner() {
        String[] banner = {
            "",
            "  ████████╗██╗   ██╗██╗    ████████╗███████╗██████╗ ███╗   ███╗██╗███╗   ██╗ █████╗ ██╗     ",
            "  ╚══██╔══╝██║   ██║██║    ╚══██╔══╝██╔════╝██╔══██╗████╗ ████║██║████╗  ██║██╔══██╗██║     ",
            "     ██║   ██║   ██║██║       ██║   █████╗  ██████╔╝██╔████╔██║██║██╔██╗ ██║███████║██║     ",
            "     ██║   ██║   ██║██║       ██║   ██╔══╝  ██╔══██╗██║╚██╔╝██║██║██║╚██╗██║██╔══██║██║     ",
            "     ██║   ╚██████╔╝██║       ██║   ███████╗██║  ██║██║ ╚═╝ ██║██║██║ ╚████║██║  ██║███████╗",
            "     ╚═╝    ╚═════╝ ╚═╝       ╚═╝   ╚══════╝╚═╝  ╚═╝╚═╝     ╚═╝╚═╝╚═╝  ╚═══╝╚═╝  ╚═╝╚══════╝",
            "                                                                                            ",
            "  Database Server - MongoDB & MinIO Compatible                                              ",
            ""
        };
        
        for (String line : banner) {
            System.out.println("\u001B[36m" + line + "\u001B[0m");
        }
    }
    
    private static void printVersion() {
        System.out.println("\u001B[33m  :: TYPUI Database :: (v" + VERSION + ")\u001B[0m");
        System.out.println("\u001B[90m  :: Build Date: " + BUILD_DATE + " ::\u001B[0m");
        System.out.println();
    }
    
    private static void printSystemInfo() {
        Runtime runtime = Runtime.getRuntime();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;
        
        String javaVersion = System.getProperty("java.version");
        String javaVendor = System.getProperty("java.vendor");
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        int processors = runtime.availableProcessors();
        
        String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        printRow("Java Version", javaVersion + " (" + javaVendor + ")");
        printRow("Java Home", System.getProperty("java.home"));
        printRow("OS", osName + " " + osVersion + " " + osArch);
        printRow("Processors", String.valueOf(processors));
        printRow("Max Memory", maxMemory + " MB");
        printRow("Memory Usage", usedMemory + " MB / " + totalMemory + " MB");
        printRow("Start Time", startTime);
        printRow("PID", getPid());
    }
    
    private static void printRow(String label, String value) {
        System.out.printf("\u001B[90m  %-14s\u001B[0m : \u001B[37m%s\u001B[0m%n", label, value);
    }
    
    private static void printDivider() {
        System.out.println();
        System.out.println("\u001B[90m  ──────────────────────────────────────────────────────────────────────\u001B[0m");
        System.out.println();
    }
    
    public static void printServiceStatus(String serviceName, String host, int port, String status) {
        String statusColor = "running".equalsIgnoreCase(status) ? "\u001B[32m" : "\u001B[31m";
        String statusIcon = "running".equalsIgnoreCase(status) ? "✓" : "✗";
        
        System.out.printf("  \u001B[36m%-20s\u001B[0m : \u001B[90mhttp://%s:%d\u001B[0m %s[%s %s]\u001B[0m%n", 
            serviceName, host, port, statusColor, statusIcon, status);
    }
    
    public static void printServiceStart(String serviceName) {
        System.out.printf("  \u001B[33m➜\u001B[0m Starting %s...\n", serviceName);
    }
    
    public static void printServiceReady(String serviceName, String host, int port) {
        System.out.printf("  \u001B[32m✓\u001B[0m %s ready at \u001B[36mhttp://%s:%d\u001B[0m\n", serviceName, host, port);
    }
    
    public static void printServiceError(String serviceName, String error) {
        System.out.printf("  \u001B[31m✗\u001B[0m %s failed: %s\n", serviceName, error);
    }
    
    public static void printCommandHelp() {
        System.out.println();
        System.out.println("\u001B[33m  Available Commands:\u001B[0m");
        System.out.println("  \u001B[90m─────────────────────────────────────\u001B[0m");
        System.out.println("  \u001B[36m  status\u001B[0m     - Show server status");
        System.out.println("  \u001B[36m  stats\u001B[0m      - Show system statistics");
        System.out.println("  \u001B[36m  logs\u001B[0m       - Show recent logs");
        System.out.println("  \u001B[36m  clear\u001B[0m      - Clear console");
        System.out.println("  \u001B[36m  help\u001B[0m       - Show this help");
        System.out.println("  \u001B[36m  exit\u001B[0m       - Shutdown server");
        System.out.println("  \u001B[90m─────────────────────────────────────\u001B[0m");
        System.out.println();
    }
    
    public static void printShutdown() {
        System.out.println();
        System.out.println("\u001B[33m  Shutting down TYPUI Database...\u001B[0m");
        System.out.println();
    }
    
    public static void printShutdownComplete() {
        System.out.println();
        System.out.println("\u001B[32m  ✓ TYPUI Database stopped successfully.\u001B[0m");
        System.out.println("\u001B[90m  Goodbye!\u001B[0m");
        System.out.println();
    }
    
    private static String getPid() {
        try {
            String jvmName = ManagementFactory.getRuntimeMXBean().getName();
            return jvmName.split("@")[0];
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    public static Map<String, Object> getVersionInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("version", VERSION);
        info.put("buildDate", BUILD_DATE);
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("osName", System.getProperty("os.name"));
        info.put("osVersion", System.getProperty("os.version"));
        info.put("osArch", System.getProperty("os.arch"));
        return info;
    }
    
    public static String getVersion() {
        return VERSION;
    }
}
