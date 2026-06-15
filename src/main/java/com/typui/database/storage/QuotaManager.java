package com.typui.database.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 配额和限速管理器
 */
public class QuotaManager {
    
    private static final Logger logger = LoggerFactory.getLogger(QuotaManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .configure(SerializationFeature.INDENT_OUTPUT, false);
    
    private final File quotaFile;
    private final Map<String, QuotaConfig> bucketQuotas;
    private final Map<String, QuotaConfig> userQuotas;
    private final Map<String, RateLimiter> rateLimiters;
    private final Map<String, AtomicLong> bandwidthUsage;
    private final ScheduledExecutorService scheduler;
    
    public QuotaManager(String dataDir) {
        this.quotaFile = new File(dataDir, "quotas.json");
        this.bucketQuotas = new ConcurrentHashMap<>();
        this.userQuotas = new ConcurrentHashMap<>();
        this.rateLimiters = new ConcurrentHashMap<>();
        this.bandwidthUsage = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QuotaManager-Scheduler");
            t.setDaemon(true);
            return t;
        });
        
        loadQuotas();
        startBandwidthReset();
    }
    
    @SuppressWarnings("unchecked")
    private void loadQuotas() {
        if (quotaFile.exists()) {
            try {
                Map<String, Object> data = objectMapper.readValue(quotaFile, Map.class);
                
                Map<String, Object> bucketQuotasMap = (Map<String, Object>) data.get("bucketQuotas");
                if (bucketQuotasMap != null) {
                    for (Map.Entry<String, Object> entry : bucketQuotasMap.entrySet()) {
                        Map<String, Object> config = (Map<String, Object>) entry.getValue();
                        bucketQuotas.put(entry.getKey(), QuotaConfig.fromMap(config));
                    }
                }
                
                Map<String, Object> userQuotasMap = (Map<String, Object>) data.get("userQuotas");
                if (userQuotasMap != null) {
                    for (Map.Entry<String, Object> entry : userQuotasMap.entrySet()) {
                        Map<String, Object> config = (Map<String, Object>) entry.getValue();
                        userQuotas.put(entry.getKey(), QuotaConfig.fromMap(config));
                    }
                }
                
                logger.info("配额配置加载完成");
            } catch (Exception e) {
                logger.error("加载配额配置失败", e);
            }
        }
    }
    
    private void saveQuotas() {
        try {
            Map<String, Object> data = new HashMap<>();
            
            Map<String, Object> bucketQuotasMap = new HashMap<>();
            for (Map.Entry<String, QuotaConfig> entry : bucketQuotas.entrySet()) {
                bucketQuotasMap.put(entry.getKey(), entry.getValue().toMap());
            }
            data.put("bucketQuotas", bucketQuotasMap);
            
            Map<String, Object> userQuotasMap = new HashMap<>();
            for (Map.Entry<String, QuotaConfig> entry : userQuotas.entrySet()) {
                userQuotasMap.put(entry.getKey(), entry.getValue().toMap());
            }
            data.put("userQuotas", userQuotasMap);
            
            quotaFile.getParentFile().mkdirs();
            objectMapper.writeValue(quotaFile, data);
        } catch (Exception e) {
            logger.error("保存配额配置失败", e);
        }
    }
    
    public void setBucketQuota(String bucket, long maxSize, long maxFiles, 
                              long maxUploadSpeed, long maxDownloadSpeed) {
        QuotaConfig config = new QuotaConfig();
        config.setTarget(bucket);
        config.setType("bucket");
        config.setMaxSize(maxSize);
        config.setMaxFiles(maxFiles);
        config.setMaxUploadSpeed(maxUploadSpeed);
        config.setMaxDownloadSpeed(maxDownloadSpeed);
        config.setCreatedAt(System.currentTimeMillis());
        
        bucketQuotas.put(bucket, config);
        saveQuotas();
        
        logger.info("存储桶配额设置成功: {} - 最大 {} bytes, {} 个文件", bucket, maxSize, maxFiles);
    }
    
    public void setUserQuota(String user, long maxSize, long maxFiles,
                            long maxUploadSpeed, long maxDownloadSpeed) {
        QuotaConfig config = new QuotaConfig();
        config.setTarget(user);
        config.setType("user");
        config.setMaxSize(maxSize);
        config.setMaxFiles(maxFiles);
        config.setMaxUploadSpeed(maxUploadSpeed);
        config.setMaxDownloadSpeed(maxDownloadSpeed);
        config.setCreatedAt(System.currentTimeMillis());
        
        userQuotas.put(user, config);
        saveQuotas();
        
        logger.info("用户配额设置成功: {} - 最大 {} bytes, {} 个文件", user, maxSize, maxFiles);
    }
    
    public QuotaCheckResult checkBucketQuota(String bucket, long additionalSize, 
                                             StorageBucket storageBucket) {
        QuotaConfig config = bucketQuotas.get(bucket);
        if (config == null) {
            return QuotaCheckResult.success();
        }
        
        long currentSize = storageBucket.getTotalSize();
        long currentFiles = storageBucket.getFileCount();
        
        if (config.getMaxSize() > 0 && currentSize + additionalSize > config.getMaxSize()) {
            return QuotaCheckResult.failure("存储空间不足，当前: " + formatSize(currentSize) + 
                ", 限制: " + formatSize(config.getMaxSize()));
        }
        
        if (config.getMaxFiles() > 0 && currentFiles >= config.getMaxFiles()) {
            return QuotaCheckResult.failure("文件数量已达上限: " + config.getMaxFiles());
        }
        
        return QuotaCheckResult.success();
    }
    
    public QuotaCheckResult checkUserQuota(String user, long additionalSize, 
                                           long currentSize, long currentFiles) {
        QuotaConfig config = userQuotas.get(user);
        if (config == null) {
            return QuotaCheckResult.success();
        }
        
        if (config.getMaxSize() > 0 && currentSize + additionalSize > config.getMaxSize()) {
            return QuotaCheckResult.failure("用户存储空间不足");
        }
        
        if (config.getMaxFiles() > 0 && currentFiles >= config.getMaxFiles()) {
            return QuotaCheckResult.failure("用户文件数量已达上限");
        }
        
        return QuotaCheckResult.success();
    }
    
    public RateLimiter getRateLimiter(String key, long bytesPerSecond) {
        return rateLimiters.computeIfAbsent(key, k -> new RateLimiter(bytesPerSecond));
    }
    
    public void recordBandwidth(String key, long bytes) {
        bandwidthUsage.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(bytes);
    }
    
    public long getBandwidthUsage(String key) {
        AtomicLong usage = bandwidthUsage.get(key);
        return usage != null ? usage.get() : 0;
    }
    
    public boolean deleteBucketQuota(String bucket) {
        QuotaConfig removed = bucketQuotas.remove(bucket);
        if (removed != null) {
            saveQuotas();
            return true;
        }
        return false;
    }
    
    public boolean deleteUserQuota(String user) {
        QuotaConfig removed = userQuotas.remove(user);
        if (removed != null) {
            saveQuotas();
            return true;
        }
        return false;
    }
    
    public Map<String, Object> listAllQuotas() {
        Map<String, Object> result = new HashMap<>();
        result.put("bucketQuotas", new HashMap<>(bucketQuotas));
        result.put("userQuotas", new HashMap<>(userQuotas));
        return result;
    }
    
    private void startBandwidthReset() {
        scheduler.scheduleAtFixedRate(() -> {
            bandwidthUsage.clear();
        }, 1, 1, TimeUnit.HOURS);
    }
    
    public void shutdown() {
        scheduler.shutdown();
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    public static class QuotaConfig {
        private String target;
        private String type;
        private long maxSize;
        private long maxFiles;
        private long maxUploadSpeed;
        private long maxDownloadSpeed;
        private long createdAt;
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("target", target);
            map.put("type", type);
            map.put("maxSize", maxSize);
            map.put("maxFiles", maxFiles);
            map.put("maxUploadSpeed", maxUploadSpeed);
            map.put("maxDownloadSpeed", maxDownloadSpeed);
            map.put("createdAt", createdAt);
            return map;
        }
        
        public static QuotaConfig fromMap(Map<String, Object> map) {
            QuotaConfig config = new QuotaConfig();
            config.setTarget((String) map.get("target"));
            config.setType((String) map.get("type"));
            config.setMaxSize(map.get("maxSize") != null ? ((Number) map.get("maxSize")).longValue() : 0);
            config.setMaxFiles(map.get("maxFiles") != null ? ((Number) map.get("maxFiles")).longValue() : 0);
            config.setMaxUploadSpeed(map.get("maxUploadSpeed") != null ? ((Number) map.get("maxUploadSpeed")).longValue() : 0);
            config.setMaxDownloadSpeed(map.get("maxDownloadSpeed") != null ? ((Number) map.get("maxDownloadSpeed")).longValue() : 0);
            config.setCreatedAt(map.get("createdAt") != null ? ((Number) map.get("createdAt")).longValue() : 0);
            return config;
        }
        
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public long getMaxSize() { return maxSize; }
        public void setMaxSize(long maxSize) { this.maxSize = maxSize; }
        public long getMaxFiles() { return maxFiles; }
        public void setMaxFiles(long maxFiles) { this.maxFiles = maxFiles; }
        public long getMaxUploadSpeed() { return maxUploadSpeed; }
        public void setMaxUploadSpeed(long maxUploadSpeed) { this.maxUploadSpeed = maxUploadSpeed; }
        public long getMaxDownloadSpeed() { return maxDownloadSpeed; }
        public void setMaxDownloadSpeed(long maxDownloadSpeed) { this.maxDownloadSpeed = maxDownloadSpeed; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    }
    
    public static class QuotaCheckResult {
        private final boolean allowed;
        private final String message;
        
        private QuotaCheckResult(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }
        
        public static QuotaCheckResult success() {
            return new QuotaCheckResult(true, null);
        }
        
        public static QuotaCheckResult failure(String message) {
            return new QuotaCheckResult(false, message);
        }
        
        public boolean isAllowed() { return allowed; }
        public String getMessage() { return message; }
    }
    
    public static class RateLimiter {
        private final long bytesPerSecond;
        private final AtomicLong tokens;
        private volatile long lastRefillTime;
        
        public RateLimiter(long bytesPerSecond) {
            this.bytesPerSecond = bytesPerSecond;
            this.tokens = new AtomicLong(bytesPerSecond);
            this.lastRefillTime = System.currentTimeMillis();
        }
        
        public boolean tryAcquire(long bytes) {
            refill();
            
            if (tokens.get() >= bytes) {
                tokens.addAndGet(-bytes);
                return true;
            }
            return false;
        }
        
        public boolean acquire(long bytes, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (tryAcquire(bytes)) {
                    return true;
                }
                Thread.yield();
            }
            return false;
        }
        
        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            
            if (elapsed >= 1000) {
                long newTokens = (elapsed / 1000) * bytesPerSecond;
                tokens.set(Math.min(bytesPerSecond, tokens.get() + newTokens));
                lastRefillTime = now;
            }
        }
        
        public long getAvailableTokens() {
            refill();
            return tokens.get();
        }
    }
}
