package com.typui.database.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typui.database.adapter.MinioAdapter;
import com.typui.database.adapter.MinioAdapterHttp;
import com.typui.database.adapter.MongoCollectionAdapter;
import com.typui.database.adapter.MongoCollectionAdapterHttp;
import com.typui.database.adapter.MongoDatabaseAdapter;
import com.typui.database.document.DocumentServer;
import com.typui.database.storage.StorageServer;

import java.io.Closeable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TYPUI数据库客户端
 * 提供统一的数据库访问接口
 */
public class TypuiDatabaseClient implements Closeable {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String documentHost;
    private final int documentPort;
    private final String storageHost;
    private final int storagePort;
    
    private final Map<String, MongoDatabaseAdapter> databaseCache;
    private MinioAdapter minioAdapter;
    
    /**
     * 创建客户端实例
     * @param documentHost 文档数据库主机
     * @param documentPort 文档数据库端口
     * @param storageHost 文件存储主机
     * @param storagePort 文件存储端口
     */
    public TypuiDatabaseClient(String documentHost, int documentPort, 
                               String storageHost, int storagePort) {
        this.documentHost = documentHost;
        this.documentPort = documentPort;
        this.storageHost = storageHost;
        this.storagePort = storagePort;
        this.databaseCache = new HashMap<>();
    }
    
    /**
     * 创建本地客户端（默认端口）
     */
    public static TypuiDatabaseClient createLocal() {
        return new TypuiDatabaseClient("localhost", 27017, "localhost", 9000);
    }
    
    /**
     * 获取文档数据库
     */
    public MongoDatabaseAdapter getDatabase(String databaseName) {
        return databaseCache.computeIfAbsent(databaseName, name -> {
            // 通过HTTP API连接
            return new MongoDatabaseAdapter(null, name) {
                @Override
                public MongoCollectionAdapter getCollection(String collectionName) {
                    return new MongoCollectionAdapterHttp(documentHost, documentPort, databaseName, collectionName);
                }
            };
        });
    }
    
    /**
     * 获取文件存储适配器
     */
    public MinioAdapter getStorageAdapter(String defaultBucket) {
        if (minioAdapter == null) {
            minioAdapter = new MinioAdapterHttp(storageHost, storagePort, defaultBucket);
        }
        return minioAdapter;
    }
    
    /**
     * 创建数据库
     */
    public void createDatabase(String databaseName) {
        try {
            String url = String.format("http://%s:%d/databases", documentHost, documentPort);
            Map<String, Object> body = new HashMap<>();
            body.put("name", databaseName);
            
            HttpUtil.post(url, body);
        } catch (Exception e) {
            throw new RuntimeException("创建数据库失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 列出所有数据库
     */
    @SuppressWarnings("unchecked")
    public List<String> listDatabases() {
        try {
            String url = String.format("http://%s:%d/databases", documentHost, documentPort);
            Map<String, Object> result = HttpUtil.get(url);
            return (List<String>) result.get("databases");
        } catch (Exception e) {
            throw new RuntimeException("获取数据库列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 创建存储桶
     */
    public void createBucket(String bucketName) {
        try {
            String url = String.format("http://%s:%d/buckets", storageHost, storagePort);
            Map<String, Object> body = new HashMap<>();
            body.put("name", bucketName);
            
            HttpUtil.post(url, body);
        } catch (Exception e) {
            throw new RuntimeException("创建存储桶失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 列出所有存储桶
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listBuckets() {
        try {
            String url = String.format("http://%s:%d/buckets", storageHost, storagePort);
            Map<String, Object> result = HttpUtil.get(url);
            return (List<Map<String, Object>>) result.get("buckets");
        } catch (Exception e) {
            throw new RuntimeException("获取存储桶列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 上传文件
     */
    public String uploadFile(String bucketName, String objectName, byte[] data, String contentType) {
        try {
            String url = String.format("http://%s:%d/buckets/%s/upload", storageHost, storagePort, bucketName);
            Map<String, Object> result = HttpUtil.uploadFile(url, objectName, data, contentType);
            return (String) result.get("objectId");
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 下载文件
     */
    public byte[] downloadFile(String bucketName, String objectId) {
        try {
            String url = String.format("http://%s:%d/buckets/%s/download/%s", 
                storageHost, storagePort, bucketName, objectId);
            return HttpUtil.download(url);
        } catch (Exception e) {
            throw new RuntimeException("文件下载失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 删除文件
     */
    public void deleteFile(String bucketName, String objectId) {
        try {
            String url = String.format("http://%s:%d/buckets/%s/%s", 
                storageHost, storagePort, bucketName, objectId);
            HttpUtil.delete(url);
        } catch (Exception e) {
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取文件URL
     */
    public String getFileUrl(String bucketName, String objectId) {
        return String.format("http://%s:%d/buckets/%s/download/%s", 
            storageHost, storagePort, bucketName, objectId);
    }
    
    /**
     * 获取预览URL
     */
    public String getPreviewUrl(String bucketName, String objectId) {
        return String.format("http://%s:%d/buckets/%s/preview/%s", 
            storageHost, storagePort, bucketName, objectId);
    }
    
    @Override
    public void close() {
        databaseCache.clear();
        if (minioAdapter != null) {
            minioAdapter.close();
        }
    }
}
