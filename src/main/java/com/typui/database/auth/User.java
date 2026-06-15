package com.typui.database.auth;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户实体类
 */
public class User {
    
    private String id;
    private String username;
    private String password;
    private String role;
    private long createdAt;
    private long updatedAt;
    private long lastLoginAt;
    private Map<String, Object> metadata;
    
    public User() {
        this.metadata = new HashMap<>();
        this.role = "user";
    }
    
    /**
     * 转换为Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("username", username);
        map.put("password", password);
        map.put("role", role);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        map.put("lastLoginAt", lastLoginAt);
        map.put("metadata", metadata);
        return map;
    }
    
    /**
     * 从Map创建
     */
    @SuppressWarnings("unchecked")
    public static User fromMap(Map<String, Object> map) {
        User user = new User();
        user.setId((String) map.get("id"));
        user.setUsername((String) map.get("username"));
        user.setPassword((String) map.get("password"));
        user.setRole((String) map.get("role"));
        user.setCreatedAt(map.get("createdAt") != null ? ((Number) map.get("createdAt")).longValue() : 0);
        user.setUpdatedAt(map.get("updatedAt") != null ? ((Number) map.get("updatedAt")).longValue() : 0);
        user.setLastLoginAt(map.get("lastLoginAt") != null ? ((Number) map.get("lastLoginAt")).longValue() : 0);
        
        Object meta = map.get("metadata");
        if (meta instanceof Map) {
            user.setMetadata((Map<String, Object>) meta);
        }
        
        return user;
    }
    
    /**
     * 转换为公开Map（不包含密码）
     */
    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("username", username);
        map.put("role", role);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        map.put("lastLoginAt", lastLoginAt);
        return map;
    }
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getRole() {
        return role;
    }
    
    public void setRole(String role) {
        this.role = role;
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
    
    public long getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public long getLastLoginAt() {
        return lastLoginAt;
    }
    
    public void setLastLoginAt(long lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }
    
    /**
     * 是否为管理员
     */
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
