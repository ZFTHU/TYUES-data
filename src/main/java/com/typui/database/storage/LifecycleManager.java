package com.typui.database.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * 生命周期管理器
 * 支持自动删除过期文件、清理旧文件
 */
public class LifecycleManager {
    
    private static final Logger logger = LoggerFactory.getLogger(LifecycleManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final File rulesFile;
    private final Map<String, LifecycleRule> rules;
    private final ScheduledExecutorService scheduler;
    private final Map<String, StorageBucket> buckets;
    
    public LifecycleManager(String dataDir, Map<String, StorageBucket> buckets) {
        this.rulesFile = new File(dataDir, "lifecycle_rules.json");
        this.rules = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.buckets = buckets;
        
        loadRules();
    }
    
    /**
     * 加载生命周期规则
     */
    @SuppressWarnings("unchecked")
    private void loadRules() {
        if (rulesFile.exists()) {
            try {
                Map<String, Object> data = objectMapper.readValue(rulesFile, Map.class);
                List<Map<String, Object>> rulesList = (List<Map<String, Object>>) data.get("rules");
                if (rulesList != null) {
                    for (Map<String, Object> ruleData : rulesList) {
                        LifecycleRule rule = LifecycleRule.fromMap(ruleData);
                        rules.put(rule.getId(), rule);
                    }
                }
                logger.info("生命周期规则加载完成，共 {} 条", rules.size());
            } catch (Exception e) {
                logger.error("加载生命周期规则失败", e);
            }
        }
    }
    
    /**
     * 保存生命周期规则
     */
    private void saveRules() {
        try {
            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> rulesList = new ArrayList<>();
            for (LifecycleRule rule : rules.values()) {
                rulesList.add(rule.toMap());
            }
            data.put("rules", rulesList);
            
            rulesFile.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(rulesFile, data);
        } catch (Exception e) {
            logger.error("保存生命周期规则失败", e);
        }
    }
    
    /**
     * 添加生命周期规则
     */
    public LifecycleRule addRule(String bucket, String prefix, int expireDays, 
                                 String action, boolean enabled) {
        String ruleId = "rule_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        
        LifecycleRule rule = new LifecycleRule();
        rule.setId(ruleId);
        rule.setBucket(bucket);
        rule.setPrefix(prefix);
        rule.setExpireDays(expireDays);
        rule.setAction(action);
        rule.setEnabled(enabled);
        rule.setCreatedAt(System.currentTimeMillis());
        
        rules.put(ruleId, rule);
        saveRules();
        
        logger.info("生命周期规则添加成功: {} - {}天后{}", ruleId, expireDays, action);
        
        return rule;
    }
    
    /**
     * 删除生命周期规则
     */
    public boolean deleteRule(String ruleId) {
        LifecycleRule removed = rules.remove(ruleId);
        if (removed != null) {
            saveRules();
            logger.info("生命周期规则删除成功: {}", ruleId);
            return true;
        }
        return false;
    }
    
    /**
     * 启用/禁用规则
     */
    public boolean toggleRule(String ruleId, boolean enabled) {
        LifecycleRule rule = rules.get(ruleId);
        if (rule != null) {
            rule.setEnabled(enabled);
            saveRules();
            logger.info("生命周期规则{}: {}", enabled ? "启用" : "禁用", ruleId);
            return true;
        }
        return false;
    }
    
    /**
     * 列出所有规则
     */
    public List<LifecycleRule> listRules() {
        return new ArrayList<>(rules.values());
    }
    
    /**
     * 获取规则
     */
    public LifecycleRule getRule(String ruleId) {
        return rules.get(ruleId);
    }
    
    /**
     * 启动定时清理任务
     */
    public void startScheduledCleanup(int intervalHours) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                executeCleanup();
            } catch (Exception e) {
                logger.error("定时清理任务执行失败", e);
            }
        }, intervalHours, intervalHours, TimeUnit.HOURS);
        
        logger.info("生命周期定时清理已启动，间隔: {} 小时", intervalHours);
    }
    
    /**
     * 停止定时清理任务
     */
    public void stopScheduledCleanup() {
        scheduler.shutdown();
        logger.info("生命周期定时清理已停止");
    }
    
    /**
     * 执行清理
     */
    public CleanupResult executeCleanup() {
        CleanupResult result = new CleanupResult();
        
        logger.info("开始执行生命周期清理...");
        
        for (LifecycleRule rule : rules.values()) {
            if (!rule.isEnabled()) {
                continue;
            }
            
            try {
                StorageBucket bucket = buckets.get(rule.getBucket());
                if (bucket == null) {
                    continue;
                }
                
                long expireTime = System.currentTimeMillis() - (rule.getExpireDays() * 24L * 60 * 60 * 1000);
                
                List<FileMetadata> files = bucket.listAll();
                for (FileMetadata file : files) {
                    if (file.getCreatedAt() < expireTime) {
                        if (rule.getPrefix() == null || rule.getPrefix().isEmpty() ||
                            file.getObjectName().startsWith(rule.getPrefix())) {
                            
                            if ("delete".equals(rule.getAction())) {
                                bucket.delete(file.getObjectId());
                                result.incrementDeleted();
                            } else if ("archive".equals(rule.getAction())) {
                                result.incrementArchived();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("执行规则 {} 失败", rule.getId(), e);
                result.incrementErrors();
            }
        }
        
        result.setCompletedAt(System.currentTimeMillis());
        
        logger.info("生命周期清理完成: 删除 {} 个文件, 归档 {} 个文件, 错误 {} 个",
            result.getDeletedCount(), result.getArchivedCount(), result.getErrorCount());
        
        return result;
    }
    
    /**
     * 生命周期规则类
     */
    public static class LifecycleRule {
        private String id;
        private String bucket;
        private String prefix;
        private int expireDays;
        private String action;
        private boolean enabled;
        private long createdAt;
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("id", id);
            map.put("bucket", bucket);
            map.put("prefix", prefix);
            map.put("expireDays", expireDays);
            map.put("action", action);
            map.put("enabled", enabled);
            map.put("createdAt", createdAt);
            return map;
        }
        
        public static LifecycleRule fromMap(Map<String, Object> map) {
            LifecycleRule rule = new LifecycleRule();
            rule.setId((String) map.get("id"));
            rule.setBucket((String) map.get("bucket"));
            rule.setPrefix((String) map.get("prefix"));
            rule.setExpireDays(map.get("expireDays") != null ? ((Number) map.get("expireDays")).intValue() : 0);
            rule.setAction((String) map.get("action"));
            rule.setEnabled(map.get("enabled") != null && Boolean.TRUE.equals(map.get("enabled")));
            rule.setCreatedAt(map.get("createdAt") != null ? ((Number) map.get("createdAt")).longValue() : 0);
            return rule;
        }
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public int getExpireDays() { return expireDays; }
        public void setExpireDays(int expireDays) { this.expireDays = expireDays; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    }
    
    /**
     * 清理结果类
     */
    public static class CleanupResult {
        private int deletedCount;
        private int archivedCount;
        private int errorCount;
        private long completedAt;
        
        public void incrementDeleted() { deletedCount++; }
        public void incrementArchived() { archivedCount++; }
        public void incrementErrors() { errorCount++; }
        
        public int getDeletedCount() { return deletedCount; }
        public int getArchivedCount() { return archivedCount; }
        public int getErrorCount() { return errorCount; }
        public long getCompletedAt() { return completedAt; }
        public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
    }
}
