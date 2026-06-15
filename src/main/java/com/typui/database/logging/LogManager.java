package com.typui.database.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class LogManager {
    
    private static final Logger logger = LoggerFactory.getLogger(LogManager.class);
    private static LogManager instance;
    
    private final String logDir;
    private final int maxLogFiles;
    private final int maxLogSizeMB;
    private final int flushInterval;
    private final AtomicLong logIdCounter;
    
    private final ConcurrentLinkedQueue<LogEntry> logBuffer;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService writerExecutor;
    private volatile boolean running;
    
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    public static class LogEntry {
        private final long timestamp;
        private final String level;
        private final String component;
        private final String message;
        private final String threadName;
        
        public LogEntry(String level, String component, String message) {
            this.timestamp = System.currentTimeMillis();
            this.level = level;
            this.component = component;
            this.message = message;
            this.threadName = Thread.currentThread().getName();
        }
        
        public long getTimestamp() { return timestamp; }
        public String getLevel() { return level; }
        public String getComponent() { return component; }
        public String getMessage() { return message; }
        public String getThreadName() { return threadName; }
        
        public String toFormattedString() {
            return String.format("[%s] [%s] [%s] %s",
                formatTimestamp(timestamp),
                level,
                component,
                message
            );
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("timestamp", timestamp);
            map.put("level", level);
            map.put("component", component);
            map.put("message", message);
            map.put("threadName", threadName);
            map.put("formatted", toFormattedString());
            return map;
        }
        
        private String formatTimestamp(long ts) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            return java.time.Instant.ofEpochMilli(ts)
                .atZone(java.time.ZoneId.systemDefault())
                .format(formatter);
        }
    }
    
    public static synchronized void initialize(String baseDir, int maxLogFiles, int maxLogSizeMB, int flushInterval) {
        if (instance == null) {
            instance = new LogManager(baseDir, maxLogFiles, maxLogSizeMB, flushInterval);
        }
    }
    
    public static LogManager getInstance() {
        return instance;
    }
    
    private LogManager(String baseDir, int maxLogFiles, int maxLogSizeMB, int flushInterval) {
        this.logDir = Paths.get(baseDir, "logs").toString();
        this.maxLogFiles = maxLogFiles;
        this.maxLogSizeMB = maxLogSizeMB;
        this.flushInterval = flushInterval;
        this.logIdCounter = new AtomicLong(0);
        this.logBuffer = new ConcurrentLinkedQueue<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LogScheduler");
            t.setDaemon(true);
            return t;
        });
        this.writerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LogWriter");
            t.setDaemon(true);
            return t;
        });
        this.running = true;
        
        initializeLogDirectory();
        startFlushTask();
        
        logger.info("日志管理器初始化完成");
    }
    
    private void initializeLogDirectory() {
        try {
            Path logPath = Paths.get(logDir);
            if (!Files.exists(logPath)) {
                Files.createDirectories(logPath);
            }
        } catch (IOException e) {
            logger.error("创建日志目录失败", e);
        }
    }
    
    private void startFlushTask() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                flushLogs();
            } catch (Exception e) {
                logger.error("刷新日志失败", e);
            }
        }, flushInterval, flushInterval, TimeUnit.SECONDS);
    }
    
    public void log(String level, String component, String message) {
        if (!running) return;
        
        LogEntry entry = new LogEntry(level, component, message);
        logBuffer.offer(entry);
        
        if (logBuffer.size() >= 100) {
            flushLogs();
        }
    }
    
    public void info(String component, String message) {
        log("INFO", component, message);
    }
    
    public void warn(String component, String message) {
        log("WARN", component, message);
    }
    
    public void error(String component, String message) {
        log("ERROR", component, message);
    }
    
    public void error(String component, String message, Exception e) {
        log("ERROR", component, message + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    
    public void debug(String component, String message) {
        log("DEBUG", component, message);
    }
    
    public void flushLogs() {
        if (logBuffer.isEmpty()) return;
        
        List<LogEntry> entries = new ArrayList<>();
        LogEntry entry;
        while ((entry = logBuffer.poll()) != null) {
            entries.add(entry);
        }
        
        if (!entries.isEmpty()) {
            writerExecutor.submit(() -> writeLogsToFile(entries));
        }
    }
    
    private void writeLogsToFile(List<LogEntry> entries) {
        String today = LocalDate.now().format(dateFormatter);
        Path logFile = Paths.get(logDir, "typui_" + today + ".log");
        
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                    new FileOutputStream(logFile.toFile(), true),
                    StandardCharsets.UTF_8
                ), 8192)) {
            
            for (LogEntry entry : entries) {
                writer.write(entry.toFormattedString());
                writer.newLine();
            }
            writer.flush();
            
        } catch (IOException e) {
            logger.error("写入日志文件失败: {}", logFile, e);
        }
    }
    
    public List<LogEntry> getRecentLogs(int limit, String level) {
        List<LogEntry> result = new ArrayList<>();
        String today = LocalDate.now().format(dateFormatter);
        Path logFile = Paths.get(logDir, "typui_" + today + ".log");
        
        if (!Files.exists(logFile)) {
            return result;
        }
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    new FileInputStream(logFile.toFile()),
                    StandardCharsets.UTF_8
                ))) {
            
            String line;
            List<String> allLines = new ArrayList<>();
            
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }
            
            Collections.reverse(allLines);
            
            for (String l : allLines) {
                if (result.size() >= limit) break;
                
                if (level == null || l.contains("[" + level + "]")) {
                    result.add(parseLogLine(l));
                }
            }
            
        } catch (IOException e) {
            logger.error("读取日志文件失败: {}", logFile, e);
        }
        
        return result;
    }
    
    private LogEntry parseLogLine(String line) {
        try {
            int idx1 = line.indexOf("[");
            int idx2 = line.indexOf("]", idx1);
            int idx3 = line.indexOf("[", idx2 + 1);
            int idx4 = line.indexOf("]", idx3);
            int idx5 = line.indexOf("[", idx4 + 1);
            int idx6 = line.indexOf("]", idx5);
            
            String timestamp = line.substring(idx1 + 1, idx2);
            String level = line.substring(idx3 + 1, idx4);
            String component = line.substring(idx5 + 1, idx6);
            String message = line.substring(idx6 + 1).trim();
            
            return new LogEntry(level, component, message);
        } catch (Exception e) {
            return new LogEntry("INFO", "System", line);
        }
    }
    
    public Map<String, Object> getLogStats() {
        Map<String, Object> stats = new HashMap<>();
        
        int archiveCount = 0;
        long totalSize = 0;
        
        try {
            Path logPath = Paths.get(logDir);
            if (Files.exists(logPath)) {
                File[] files = logPath.toFile().listFiles((dir, name) -> name.endsWith(".log"));
                if (files != null) {
                    archiveCount = files.length;
                    for (File file : files) {
                        totalSize += file.length();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("获取日志统计失败", e);
        }
        
        stats.put("archiveCount", archiveCount);
        stats.put("totalSize", totalSize);
        stats.put("totalSizeFormatted", formatSize(totalSize));
        stats.put("bufferSize", logBuffer.size());
        
        return stats;
    }
    
    public void forceClean() {
        try {
            Path logPath = Paths.get(logDir);
            if (!Files.exists(logPath)) {
                return;
            }
            
            File[] files = logPath.toFile().listFiles((dir, name) -> name.endsWith(".log"));
            if (files != null && files.length > maxLogFiles) {
                Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                
                for (int i = 0; i < files.length - maxLogFiles; i++) {
                    Files.delete(files[i].toPath());
                }
            }
            
            flushLogs();
        } catch (IOException e) {
            logger.error("强制清理日志失败", e);
        }
    }
    
    public void forceCompress() {
        try {
            Path logPath = Paths.get(logDir);
            if (!Files.exists(logPath)) {
                return;
            }
            
            File[] files = logPath.toFile().listFiles((dir, name) -> name.endsWith(".log"));
            if (files != null) {
                for (File file : files) {
                    if (file.length() > maxLogSizeMB * 1024 * 1024) {
                        rotateLogFile(file);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("强制压缩日志失败", e);
        }
    }
    
    private void rotateLogFile(File file) throws IOException {
        String name = file.getName();
        String baseName = name.substring(0, name.lastIndexOf('.'));
        String timestamp = String.valueOf(System.currentTimeMillis());
        
        Path rotatedPath = Paths.get(logDir, baseName + "_" + timestamp + ".log");
        Files.move(file.toPath(), rotatedPath);
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    public void shutdown() {
        if (!running) return;
        
        running = false;
        
        flushLogs();
        
        scheduler.shutdown();
        writerExecutor.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            writerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("日志管理器已关闭");
    }
}
