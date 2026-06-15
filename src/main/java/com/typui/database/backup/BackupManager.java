package com.typui.database.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.typui.database.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 备份管理器
 * 负责数据快照和回滚机制
 */
public class BackupManager {
    
    private static final Logger logger = LoggerFactory.getLogger(BackupManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .configure(SerializationFeature.INDENT_OUTPUT, false);
    
    private final ConfigManager configManager;
    private final Map<String, Object> lastSnapshotData;
    private long lastSnapshotTime;
    
    public BackupManager(ConfigManager configManager) {
        this.configManager = configManager;
        this.lastSnapshotData = new ConcurrentHashMap<>();
        this.lastSnapshotTime = 0;
    }
    
    /**
     * 创建快照
     */
    @SuppressWarnings("unchecked")
    public void createSnapshot(String snapshotPath) {
        try {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("timestamp", System.currentTimeMillis());
            snapshot.put("databaseName", configManager.getDatabaseName());
            
            Map<String, Object> data = collectAllData();
            snapshot.put("data", data);
            
            Path path = Paths.get(snapshotPath);
            Files.createDirectories(path.getParent());
            
            objectMapper.writeValue(path.toFile(), snapshot);
            
            lastSnapshotTime = System.currentTimeMillis();
            lastSnapshotData.clear();
            lastSnapshotData.putAll(data);
            
            logger.debug("Snapshot created: {}", snapshotPath);
        } catch (Exception e) {
            logger.error("Failed to create snapshot", e);
        }
    }
    
    /**
     * 收集所有数据
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> collectAllData() {
        Map<String, Object> allData = new HashMap<>();
        
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir);
        
        if (dbPath.exists() && dbPath.isDirectory()) {
            File[] collectionDirs = dbPath.listFiles(File::isDirectory);
            if (collectionDirs != null) {
                for (File collectionDir : collectionDirs) {
                    if (collectionDir.getName().startsWith(".")) continue;
                    
                    String collectionName = collectionDir.getName();
                    List<Map<String, Object>> documents = new ArrayList<>();
                    
                    File[] jsonFiles = collectionDir.listFiles((dir, name) -> name.endsWith(".json"));
                    if (jsonFiles != null) {
                        for (File jsonFile : jsonFiles) {
                            try {
                                Map<String, Object> doc = objectMapper.readValue(jsonFile, Map.class);
                                documents.add(doc);
                            } catch (Exception e) {
                                logger.warn("Failed to read document: {}", jsonFile.getName());
                            }
                        }
                    }
                    
                    allData.put(collectionName, documents);
                }
            }
        }
        
        return allData;
    }
    
    /**
     * 从快照恢复
     */
    @SuppressWarnings("unchecked")
    public void restoreSnapshot(String snapshotPath) {
        try {
            File snapshotFile = new File(snapshotPath);
            if (!snapshotFile.exists()) {
                logger.error("Snapshot file not found: {}", snapshotPath);
                return;
            }
            
            Map<String, Object> snapshot = objectMapper.readValue(snapshotFile, Map.class);
            Long timestamp = (Long) snapshot.getOrDefault("timestamp", 0L);
            
            logger.info("Restoring from snapshot... Timestamp: {}", timestamp);
            
            Map<String, Object> data = (Map<String, Object>) snapshot.get("data");
            if (data == null) {
                logger.error("Snapshot data is empty");
                return;
            }
            
            restoreDataToDirectories(data);
            
            logger.info("Data restored successfully");
        } catch (Exception e) {
            logger.error("Failed to restore snapshot", e);
        }
    }
    
    /**
     * 将恢复的数据写入目录
     */
    @SuppressWarnings("unchecked")
    private void restoreDataToDirectories(Map<String, Object> data) throws IOException {
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir);
        
        if (!dbPath.exists()) {
            dbPath.mkdirs();
        }
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String collectionName = entry.getKey();
            List<Map<String, Object>> documents = (List<Map<String, Object>>) entry.getValue();
            
            File collectionDir = new File(dbPath, collectionName);
            if (!collectionDir.exists()) {
                collectionDir.mkdirs();
            } else {
                cleanDirectory(collectionDir);
            }
            
            for (Map<String, Object> doc : documents) {
                String id = (String) doc.get("_id");
                if (id != null) {
                    File docFile = new File(collectionDir, id + ".json");
                    objectMapper.writeValue(docFile, doc);
                }
            }
        }
    }
    
    /**
     * 清空目录
     */
    private void cleanDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                } else if (file.isDirectory()) {
                    deleteDirectory(file);
                }
            }
        }
    }
    
    /**
     * 递归删除目录
     */
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
    
    public long getLastSnapshotTime() {
        return lastSnapshotTime;
    }
    
    public List<Map<String, Object>> listSnapshots() {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        
        try {
            Path snapshotPath = Paths.get(".snapshots");
            if (!Files.exists(snapshotPath)) {
                return snapshots;
            }
            
            List<Path> files = Files.list(snapshotPath)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted((a, b) -> b.compareTo(a))
                .collect(Collectors.toList());
            
            for (Path file : files) {
                Map<String, Object> info = new HashMap<>();
                info.put("path", file.toString());
                info.put("name", file.getFileName().toString());
                info.put("size", Files.size(file));
                info.put("lastModified", Files.getLastModifiedTime(file).toMillis());
                snapshots.add(info);
            }
        } catch (Exception e) {
            logger.error("Failed to list snapshots", e);
        }
        
        return snapshots;
    }
    
    public boolean deleteSnapshot(String snapshotPath) {
        try {
            Path path = Paths.get(snapshotPath);
            if (Files.exists(path)) {
                Files.delete(path);
                logger.info("Snapshot deleted: {}", snapshotPath);
                return true;
            }
        } catch (Exception e) {
            logger.error("Failed to delete snapshot", e);
        }
        return false;
    }
    
    public void cleanupSnapshots(int keepCount) {
        try {
            Path snapshotPath = Paths.get(".snapshots");
            if (!Files.exists(snapshotPath)) {
                return;
            }
            
            List<Path> snapshots = Files.list(snapshotPath)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted((a, b) -> b.compareTo(a))
                .collect(Collectors.toList());
            
            for (int i = keepCount; i < snapshots.size(); i++) {
                Files.delete(snapshots.get(i));
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup snapshots", e);
        }
    }
}
