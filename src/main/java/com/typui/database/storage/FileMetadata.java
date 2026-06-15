package com.typui.database.storage;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件元数据
 */
public class FileMetadata {

    private String objectId;
    private String objectName;
    private String storedName;
    private String contentType;
    private long size;
    private String hash;
    private String category;
    private String categoryDir;
    private String extension;
    private String uploader;
    private long createdAt;
    private long updatedAt;
    private boolean publicRead;
    private Map<String, Object> metadata;

    public FileMetadata() {
        this.metadata = new HashMap<>();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("objectId", objectId);
        map.put("objectName", objectName);
        map.put("storedName", storedName);
        map.put("contentType", contentType);
        map.put("size", size);
        map.put("hash", hash);
        map.put("category", category);
        map.put("categoryDir", categoryDir);
        map.put("extension", extension);
        map.put("uploader", uploader);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        map.put("publicRead", publicRead);
        map.put("metadata", metadata);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static FileMetadata fromMap(Map<String, Object> map) {
        FileMetadata fm = new FileMetadata();
        fm.setObjectId((String) map.get("objectId"));
        fm.setObjectName((String) map.get("objectName"));
        fm.setStoredName((String) map.get("storedName"));
        fm.setContentType((String) map.get("contentType"));
        if (map.get("size") instanceof Number) {
            fm.setSize(((Number) map.get("size")).longValue());
        }
        fm.setHash((String) map.get("hash"));
        fm.setCategory((String) map.get("category"));
        fm.setCategoryDir((String) map.get("categoryDir"));
        fm.setExtension((String) map.get("extension"));
        fm.setUploader((String) map.get("uploader"));
        if (map.get("createdAt") instanceof Number) {
            fm.setCreatedAt(((Number) map.get("createdAt")).longValue());
        }
        if (map.get("updatedAt") instanceof Number) {
            fm.setUpdatedAt(((Number) map.get("updatedAt")).longValue());
        }
        Object pubRead = map.get("publicRead");
        if (pubRead instanceof Boolean) {
            fm.setPublicRead((Boolean) pubRead);
        }
        Object meta = map.get("metadata");
        if (meta instanceof Map) {
            fm.setMetadata((Map<String, Object>) meta);
        }
        return fm;
    }

    // Getters and Setters
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }

    public String getObjectName() { return objectName; }
    public void setObjectName(String objectName) { this.objectName = objectName; }

    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getCategoryDir() { return categoryDir; }
    public void setCategoryDir(String categoryDir) { this.categoryDir = categoryDir; }

    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }

    public String getUploader() { return uploader; }
    public void setUploader(String uploader) { this.uploader = uploader; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public boolean isPublicRead() { return publicRead; }
    public void setPublicRead(boolean publicRead) { this.publicRead = publicRead; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }
}
