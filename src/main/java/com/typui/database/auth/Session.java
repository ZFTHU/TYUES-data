package com.typui.database.auth;

/**
 * 会话实体类
 */
public class Session {
    
    private String token;
    private String username;
    private long createdAt;
    private long expiresAt;
    
    public Session() {
    }
    
    /**
     * 检查会话是否过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
    
    /**
     * 获取剩余有效时间（毫秒）
     */
    public long getRemainingTime() {
        long remaining = expiresAt - System.currentTimeMillis();
        return remaining > 0 ? remaining : 0;
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
    
    public long getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
