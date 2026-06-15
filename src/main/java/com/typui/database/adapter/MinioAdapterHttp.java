package com.typui.database.adapter;

import com.typui.database.client.HttpUtil;
import com.typui.database.storage.FileMetadata;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

/**
 * MinIO兼容HTTP适配器
 * 通过HTTP API访问文件存储
 */
public class MinioAdapterHttp extends MinioAdapter {
    
    private final String host;
    private final int port;
    private final String defaultBucket;
    
    public MinioAdapterHttp(String host, int port, String defaultBucket) {
        super(null, defaultBucket);
        this.host = host;
        this.port = port;
        this.defaultBucket = defaultBucket;
    }
    
    private String getBaseUrl() {
        return String.format("http://%s:%d", host, port);
    }
    
    @Override
    public boolean bucketExists(String bucketName) {
        try {
            String url = getBaseUrl() + "/buckets/" + bucketName;
            HttpUtil.get(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void makeBucket(String bucketName) {
        try {
            String url = getBaseUrl() + "/buckets";
            Map<String, Object> body = new HashMap<>();
            body.put("name", bucketName);
            HttpUtil.post(url, body);
        } catch (Exception e) {
            throw new RuntimeException("创建存储桶失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void putObject(String bucketName, String objectName, byte[] data, String contentType) {
        try {
            String url = getBaseUrl() + "/buckets/" + bucketName + "/upload";
            HttpUtil.uploadFile(url, objectName, data, contentType);
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void putObject(String bucketName, String objectName, InputStream stream, 
                          long size, String contentType) {
        try {
            byte[] data = stream.readAllBytes();
            putObject(bucketName, objectName, data, contentType);
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public InputStream getObject(String bucketName, String objectName) {
        byte[] data = getObjectBytes(bucketName, objectName);
        return data != null ? new ByteArrayInputStream(data) : null;
    }
    
    @Override
    public byte[] getObjectBytes(String bucketName, String objectName) {
        try {
            String url = getBaseUrl() + "/buckets/" + bucketName + "/download/" + objectName;
            return HttpUtil.download(url);
        } catch (Exception e) {
            return null;
        }
    }
    
    @Override
    public void removeObject(String bucketName, String objectName) {
        try {
            String url = getBaseUrl() + "/buckets/" + bucketName + "/" + objectName;
            HttpUtil.delete(url);
        } catch (Exception e) {
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean objectExists(String bucketName, String objectName) {
        try {
            String url = getBaseUrl() + "/buckets/" + bucketName + "/metadata/" + objectName;
            HttpUtil.get(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public String getObjectUrl(String bucketName, String objectName) {
        return getBaseUrl() + "/buckets/" + bucketName + "/download/" + objectName;
    }
    
    @Override
    public FileMetadata statObject(String bucketName, String objectName) {
        try {
            String url = getBaseUrl() + "/buckets/" + bucketName + "/metadata/" + objectName;
            Map<String, Object> result = HttpUtil.get(url);
            @SuppressWarnings("unchecked")
            Map<String, Object> metaMap = (Map<String, Object>) result.get("metadata");
            if (metaMap != null) {
                return FileMetadata.fromMap(metaMap);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public List<FileMetadata> listObjects(String bucketName) {
        try {
            String url = getBaseUrl() + "/buckets/" + bucketName + "/files";
            Map<String, Object> result = HttpUtil.get(url);
            List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
            
            List<FileMetadata> metadata = new ArrayList<>();
            if (files != null) {
                for (Map<String, Object> file : files) {
                    metadata.add(FileMetadata.fromMap(file));
                }
            }
            return metadata;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public List<FileMetadata> listObjectsByCategory(String bucketName, String category) {
        try {
            String url = getBaseUrl() + "/buckets/" + bucketName + "/files/" + category;
            Map<String, Object> result = HttpUtil.get(url);
            List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
            
            List<FileMetadata> metadata = new ArrayList<>();
            if (files != null) {
                for (Map<String, Object> file : files) {
                    metadata.add(FileMetadata.fromMap(file));
                }
            }
            return metadata;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    
    @Override
    public String getDefaultBucket() {
        return defaultBucket;
    }
    
    @Override
    public void close() {
        // 无需清理
    }
}
