package com.typui.database.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志和监控系统
 * 支持慢查询日志、错误日志、访问统计
 */
public class MonitorService {
    
    private static final Logger logger = LoggerFactory.getLogger(MonitorService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final File logDir;
    private final Queue<LogEntry> slowQueryLog;
    private final Queue<LogEntry> errorLog;
    private final Queue<LogEntry> accessLog;
    private final Map<String, AccessStats> accessStats;
    private final Map<String, AtomicLong> operationCounters;
    private final long slowQueryThreshold;
    private final int maxLogEntries;
    
    public MonitorService(String dataDir, long slowQueryThreshold, int maxLogEntries) {
        this.logDir = new File(dataDir, "logs");
        this.slowQueryLog = new ConcurrentLinkedQueue<>();
        this.errorLog = new ConcurrentLinkedQueue<>();
        this.accessLog = new ConcurrentLinkedQueue<>();
        this.accessStats = new ConcurrentHashMap<>();
        this.operationCounters = new ConcurrentHashMap<>();
        this.slowQueryThreshold = slowQueryThreshold;
        this.maxLogEntries = maxLogEntries;
        
        initializeLogDir();
    }
    
    /**
     * 初始化日志目录
     */
    private void initializeLogDir() {
        if (!logDir.exists()) {
            logDir.mkdirs();
            logger.info("日志目录创建成功: {}", logDir.getAbsolutePath());
        }
    }
    
    /**
     * 记录慢查询
     */
    public void logSlowQuery(String operation, String collection, Map<String, Object> query, 
                            long duration, String detail) {
        if (duration >= slowQueryThreshold) {
            LogEntry entry = new LogEntry();
            entry.setTimestamp(System.currentTimeMillis());
            entry.setType("slow_query");
            entry.setOperation(operation);
            entry.setCollection(collection);
            entry.setQuery(query);
            entry.setDuration(duration);
            entry.setDetail(detail);
            
            slowQueryLog.offer(entry);
            trimLogQueue(slowQueryLog);
            
            logger.warn("慢查询: {} - {}ms - {}", operation, duration, collection);
        }
    }
    
    /**
     * 记录错误
     */
    public void logError(String operation, String collection, String error, String stackTrace) {
        LogEntry entry = new LogEntry();
        entry.setTimestamp(System.currentTimeMillis());
        entry.setType("error");
        entry.setOperation(operation);
        entry.setCollection(collection);
        entry.setError(error);
        entry.setStackTrace(stackTrace);
        
        errorLog.offer(entry);
        trimLogQueue(errorLog);
        
        logger.error("操作错误: {} - {} - {}", operation, collection, error);
    }
    
    /**
     * 记录访问
     */
    public void logAccess(String operation, String collection, String apiKey, 
                         String ip, long duration, boolean success) {
        LogEntry entry = new LogEntry();
        entry.setTimestamp(System.currentTimeMillis());
        entry.setType("access");
        entry.setOperation(operation);
        entry.setCollection(collection);
        entry.setApiKey(apiKey);
        entry.setIp(ip);
        entry.setDuration(duration);
        entry.setSuccess(success);
        
        accessLog.offer(entry);
        trimLogQueue(accessLog);
        
        String key = operation + ":" + collection;
        accessStats.computeIfAbsent(key, k -> new AccessStats()).record(success, duration);
        
        operationCounters.computeIfAbsent(operation, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * 获取慢查询日志
     */
    public List<LogEntry> getSlowQueryLog(int limit) {
        List<LogEntry> result = new ArrayList<>();
        int count = 0;
        for (LogEntry entry : slowQueryLog) {
            result.add(entry);
            if (++count >= limit) break;
        }
        return result;
    }
    
    /**
     * 获取错误日志
     */
    public List<LogEntry> getErrorLog(int limit) {
        List<LogEntry> result = new ArrayList<>();
        int count = 0;
        for (LogEntry entry : errorLog) {
            result.add(entry);
            if (++count >= limit) break;
        }
        return result;
    }
    
    /**
     * 获取访问日志
     */
    public List<LogEntry> getAccessLog(int limit) {
        List<LogEntry> result = new ArrayList<>();
        int count = 0;
        for (LogEntry entry : accessLog) {
            result.add(entry);
            if (++count >= limit) break;
        }
        return result;
    }
    
    /**
     * 获取访问统计
     */
    public Map<String, Object> getAccessStats() {
        Map<String, Object> result = new HashMap<>();
        
        for (Map.Entry<String, AccessStats> entry : accessStats.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toMap());
        }
        
        return result;
    }
    
    /**
     * 获取操作计数
     */
    public Map<String, Long> getOperationCounts() {
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : operationCounters.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }
    
    /**
     * 获取系统统计
     */
    public Map<String, Object> getSystemStats() {
        Map<String, Object> stats = new HashMap<>();
        
        Runtime runtime = Runtime.getRuntime();
        
        stats.put("memory", Map.of(
            "total", runtime.totalMemory(),
            "free", runtime.freeMemory(),
            "used", runtime.totalMemory() - runtime.freeMemory(),
            "max", runtime.maxMemory()
        ));
        
        stats.put("processors", runtime.availableProcessors());
        
        stats.put("threads", Map.of(
            "active", Thread.activeCount(),
            "peak", Thread.activeCount()
        ));
        
        stats.put("logs", Map.of(
            "slowQueries", slowQueryLog.size(),
            "errors", errorLog.size(),
            "access", accessLog.size()
        ));
        
        stats.put("uptime", System.currentTimeMillis());
        
        return stats;
    }
    
    /**
     * 清理日志
     */
    public void clearLogs() {
        slowQueryLog.clear();
        errorLog.clear();
        accessLog.clear();
        accessStats.clear();
        operationCounters.clear();
        
        logger.info("日志已清理");
    }
    
    /**
     * 导出日志到文件
     */
    public void exportLogs(String filename) {
        try {
            File file = new File(logDir, filename);
            
            Map<String, Object> data = new HashMap<>();
            data.put("exportTime", System.currentTimeMillis());
            data.put("slowQueries", new ArrayList<>(slowQueryLog));
            data.put("errors", new ArrayList<>(errorLog));
            data.put("accessLog", new ArrayList<>(accessLog));
            data.put("stats", getAccessStats());
            
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, data);
            
            logger.info("日志导出成功: {}", file.getAbsolutePath());
        } catch (Exception e) {
            logger.error("导出日志失败", e);
        }
    }
    
    /**
     * 修剪日志队列
     */
    private void trimLogQueue(Queue<LogEntry> queue) {
        while (queue.size() > maxLogEntries) {
            queue.poll();
        }
    }
    
    /**
     * 日志条目类
     */
    public static class LogEntry {
        private long timestamp;
        private String type;
        private String operation;
        private String collection;
        private Map<String, Object> query;
        private long duration;
        private String detail;
        private String error;
        private String stackTrace;
        private String apiKey;
        private String ip;
        private boolean success;
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("timestamp", timestamp);
            map.put("type", type);
            map.put("operation", operation);
            map.put("collection", collection);
            map.put("query", query);
            map.put("duration", duration);
            map.put("detail", detail);
            map.put("error", error);
            map.put("stackTrace", stackTrace);
            map.put("apiKey", apiKey);
            map.put("ip", ip);
            map.put("success", success);
            return map;
        }
        
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getOperation() { return operation; }
        public void setOperation(String operation) { this.operation = operation; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public Map<String, Object> getQuery() { return query; }
        public void setQuery(Map<String, Object> query) { this.query = query; }
        public long getDuration() { return duration; }
        public void setDuration(long duration) { this.duration = duration; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public String getStackTrace() { return stackTrace; }
        public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
    }
    
    /**
     * 访问统计类
     */
    public static class AccessStats {
        private long totalCount;
        private long successCount;
        private long failureCount;
        private long totalDuration;
        private long maxDuration;
        private long minDuration;
        
        public AccessStats() {
            this.totalCount = 0;
            this.successCount = 0;
            this.failureCount = 0;
            this.totalDuration = 0;
            this.maxDuration = 0;
            this.minDuration = Long.MAX_VALUE;
        }
        
        public synchronized void record(boolean success, long duration) {
            totalCount++;
            if (success) {
                successCount++;
            } else {
                failureCount++;
            }
            totalDuration += duration;
            maxDuration = Math.max(maxDuration, duration);
            minDuration = Math.min(minDuration, duration);
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("totalCount", totalCount);
            map.put("successCount", successCount);
            map.put("failureCount", failureCount);
            map.put("totalDuration", totalDuration);
            map.put("maxDuration", maxDuration);
            map.put("minDuration", minDuration == Long.MAX_VALUE ? 0 : minDuration);
            map.put("avgDuration", totalCount > 0 ? totalDuration / totalCount : 0);
            return map;
        }
    }
}
