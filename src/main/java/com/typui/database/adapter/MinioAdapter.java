package com.typui.database.adapter;

import com.typui.database.storage.FileMetadata;
import com.typui.database.storage.StorageBucket;
import com.typui.database.storage.StorageServer;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MinIO兼容存储服务适配器
 * 模拟MinIO的MinioClient接口
 */
public class MinioAdapter implements Closeable {
    
    private final StorageServer storageServer;
    private final String defaultBucket;
    
    public MinioAdapter(StorageServer storageServer, String defaultBucket) {
        this.storageServer = storageServer;
        this.defaultBucket = defaultBucket;
        if (this.storageServer != null) {
            this.storageServer.start();
        }
    }
    
    /**
     * 检查存储桶是否存在
     */
    public boolean bucketExists(String bucketName) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        return bucket != null;
    }
    
    /**
     * 创建存储桶
     */
    public void makeBucket(String bucketName) {
        // 存储桶会在第一次上传时自动创建
    }
    
    /**
     * 上传文件
     */
    public void putObject(String bucketName, String objectName, InputStream stream, 
                          long size, String contentType) {
        try {
            byte[] data = stream.readAllBytes();
            StorageBucket bucket = getOrCreateBucket(bucketName);
            bucket.upload(objectName, data, contentType, "system");
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 上传文件（字节数组）
     */
    public void putObject(String bucketName, String objectName, byte[] data, String contentType) {
        StorageBucket bucket = getOrCreateBucket(bucketName);
        bucket.upload(objectName, data, contentType, "system");
    }
    
    /**
     * 获取文件
     */
    public InputStream getObject(String bucketName, String objectName) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket == null) {
            return null;
        }
        
        byte[] data = bucket.downloadByName(objectName);
        if (data == null) {
            // 尝试按ID查找
            data = bucket.download(objectName);
        }
        
        return data != null ? new ByteArrayInputStream(data) : null;
    }
    
    /**
     * 获取文件字节数组
     */
    public byte[] getObjectBytes(String bucketName, String objectName) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket == null) {
            return null;
        }
        
        byte[] data = bucket.downloadByName(objectName);
        if (data == null) {
            data = bucket.download(objectName);
        }
        
        return data;
    }
    
    /**
     * 删除文件
     */
    public void removeObject(String bucketName, String objectName) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket != null) {
            // 先尝试按名称删除
            FileMetadata metadata = bucket.getMetadataByName(objectName);
            if (metadata != null) {
                bucket.delete(metadata.getObjectId());
            } else {
                // 尝试按ID删除
                bucket.delete(objectName);
            }
        }
    }
    
    /**
     * 检查文件是否存在
     */
    public boolean objectExists(String bucketName, String objectName) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket == null) {
            return false;
        }
        
        return bucket.existsByName(objectName) || bucket.exists(objectName);
    }
    
    /**
     * 获取文件URL
     */
    public String getObjectUrl(String bucketName, String objectName) {
        return "http://localhost:9000/buckets/" + bucketName + "/download/" + objectName;
    }
    
    /**
     * 获取文件元数据
     */
    public FileMetadata statObject(String bucketName, String objectName) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket == null) {
            return null;
        }
        
        FileMetadata metadata = bucket.getMetadataByName(objectName);
        if (metadata == null) {
            metadata = bucket.getMetadata(objectName);
        }
        
        return metadata;
    }
    
    /**
     * 列出存储桶中的所有文件
     */
    public List<FileMetadata> listObjects(String bucketName) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket == null) {
            return List.of();
        }
        return bucket.listAll();
    }
    
    /**
     * 按分类列出文件
     */
    public List<FileMetadata> listObjectsByCategory(String bucketName, String category) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket == null) {
            return List.of();
        }
        return bucket.listByCategory(category);
    }
    
    /**
     * 按扩展名列出文件
     */
    public List<FileMetadata> listObjectsByExtension(String bucketName, String extension) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket == null) {
            return List.of();
        }
        return bucket.listByExtension(extension);
    }
    
    /**
     * 获取或创建存储桶
     */
    private StorageBucket getOrCreateBucket(String bucketName) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket == null) {
            // 这里需要通过API创建，暂时返回null让上传时自动创建
            throw new RuntimeException("存储桶不存在: " + bucketName);
        }
        return bucket;
    }
    
    /**
     * 获取存储桶统计信息
     */
    public Map<String, Object> getBucketStats(String bucketName) {
        StorageBucket bucket = storageServer.getBucket(bucketName);
        if (bucket == null) {
            return Map.of("exists", false);
        }
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("exists", true);
        stats.put("name", bucketName);
        stats.put("fileCount", bucket.getFileCount());
        stats.put("totalSize", bucket.getTotalSize());
        stats.put("directoryStructure", bucket.getDirectoryStructure());
        return stats;
    }
    
    /**
     * 获取默认存储桶名称
     */
    public String getDefaultBucket() {
        return defaultBucket;
    }
    
    @Override
    public void close() {
        // 清理资源
    }
}
