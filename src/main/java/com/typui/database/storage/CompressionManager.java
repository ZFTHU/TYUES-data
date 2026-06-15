package com.typui.database.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/**
 * 文件压缩和去重管理器
 * 支持文件压缩、哈希去重
 */
public class CompressionManager {
    
    private static final Logger logger = LoggerFactory.getLogger(CompressionManager.class);
    private static final int BUFFER_SIZE = 8192;
    private static final String[] COMPRESSIBLE_TYPES = {
        "text/", "application/json", "application/xml", "application/javascript",
        "application/x-javascript", "application/xhtml+xml"
    };
    
    private final Map<String, String> hashStore;
    private final Map<String, Integer> hashRefCount;
    private final boolean enableCompression;
    private final boolean enableDeduplication;
    private final int compressionLevel;
    private final long minCompressSize;
    
    public CompressionManager(boolean enableCompression, boolean enableDeduplication,
                             int compressionLevel, long minCompressSize) {
        this.hashStore = new ConcurrentHashMap<>();
        this.hashRefCount = new ConcurrentHashMap<>();
        this.enableCompression = enableCompression;
        this.enableDeduplication = enableDeduplication;
        this.compressionLevel = compressionLevel;
        this.minCompressSize = minCompressSize;
    }
    
    /**
     * 处理文件（压缩和去重）
     */
    public ProcessResult processFile(byte[] data, String contentType) {
        ProcessResult result = new ProcessResult();
        result.setOriginalSize(data.length);
        result.setContentType(contentType);
        
        String originalHash = calculateHash(data);
        result.setOriginalHash(originalHash);
        
        if (enableDeduplication) {
            String existingId = hashStore.get(originalHash);
            if (existingId != null) {
                result.setDeduplicated(true);
                result.setExistingId(existingId);
                result.setFinalSize(0);
                result.setSavedSize(data.length);
                
                hashRefCount.merge(originalHash, 1, Integer::sum);
                
                logger.debug("文件去重命中: hash={}", originalHash);
                return result;
            }
        }
        
        byte[] processedData = data;
        boolean compressed = false;
        
        if (enableCompression && shouldCompress(data.length, contentType)) {
            byte[] compressedData = compress(data);
            
            if (compressedData.length < data.length) {
                processedData = compressedData;
                compressed = true;
                result.setCompressed(true);
                result.setCompressionRatio((double) compressedData.length / data.length);
            }
        }
        
        result.setData(processedData);
        result.setFinalSize(processedData.length);
        result.setSavedSize(data.length - processedData.length);
        result.setCompressed(compressed);
        
        if (enableDeduplication) {
            hashStore.put(originalHash, originalHash);
            hashRefCount.merge(originalHash, 1, Integer::sum);
        }
        
        return result;
    }
    
    /**
     * 解压缩文件
     */
    public byte[] decompress(byte[] data, boolean isCompressed) {
        if (!isCompressed) {
            return data;
        }
        
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             GZIPInputStream gis = new GZIPInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                bos.write(buffer, 0, len);
            }
            
            return bos.toByteArray();
        } catch (Exception e) {
            logger.error("解压缩失败", e);
            return data;
        }
    }
    
    /**
     * 压缩数据
     */
    private byte[] compress(byte[] data) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gos = new GZIPOutputStream(bos) {
                 { def.setLevel(compressionLevel); }
             }) {
            
            gos.write(data);
            gos.finish();
            
            return bos.toByteArray();
        } catch (Exception e) {
            logger.error("压缩失败", e);
            return data;
        }
    }
    
    /**
     * 判断是否应该压缩
     */
    private boolean shouldCompress(long size, String contentType) {
        if (size < minCompressSize) {
            return false;
        }
        
        if (contentType == null) {
            return false;
        }
        
        for (String type : COMPRESSIBLE_TYPES) {
            if (contentType.startsWith(type)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 计算哈希
     */
    private String calculateHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
    
    /**
     * 移除哈希引用
     */
    public void removeHashReference(String hash) {
        if (!enableDeduplication || hash == null) {
            return;
        }
        
        Integer count = hashRefCount.get(hash);
        if (count != null) {
            if (count <= 1) {
                hashRefCount.remove(hash);
                hashStore.remove(hash);
                logger.debug("哈希引用已清理: {}", hash);
            } else {
                hashRefCount.put(hash, count - 1);
            }
        }
    }
    
    /**
     * 获取去重统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("uniqueHashes", hashStore.size());
        stats.put("totalReferences", hashRefCount.values().stream().mapToInt(Integer::intValue).sum());
        stats.put("deduplicationEnabled", enableDeduplication);
        stats.put("compressionEnabled", enableCompression);
        
        return stats;
    }
    
    /**
     * 清理所有哈希
     */
    public void clear() {
        hashStore.clear();
        hashRefCount.clear();
        logger.info("压缩去重缓存已清理");
    }
    
    /**
     * 处理结果类
     */
    public static class ProcessResult {
        private byte[] data;
        private long originalSize;
        private long finalSize;
        private long savedSize;
        private boolean compressed;
        private boolean deduplicated;
        private String originalHash;
        private String existingId;
        private String contentType;
        private double compressionRatio;
        
        public byte[] getData() { return data; }
        public void setData(byte[] data) { this.data = data; }
        public long getOriginalSize() { return originalSize; }
        public void setOriginalSize(long originalSize) { this.originalSize = originalSize; }
        public long getFinalSize() { return finalSize; }
        public void setFinalSize(long finalSize) { this.finalSize = finalSize; }
        public long getSavedSize() { return savedSize; }
        public void setSavedSize(long savedSize) { this.savedSize = savedSize; }
        public boolean isCompressed() { return compressed; }
        public void setCompressed(boolean compressed) { this.compressed = compressed; }
        public boolean isDeduplicated() { return deduplicated; }
        public void setDeduplicated(boolean deduplicated) { this.deduplicated = deduplicated; }
        public String getOriginalHash() { return originalHash; }
        public void setOriginalHash(String originalHash) { this.originalHash = originalHash; }
        public String getExistingId() { return existingId; }
        public void setExistingId(String existingId) { this.existingId = existingId; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public double getCompressionRatio() { return compressionRatio; }
        public void setCompressionRatio(double compressionRatio) { this.compressionRatio = compressionRatio; }
    }
}
