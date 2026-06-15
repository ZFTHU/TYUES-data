package com.typui.database.auth;

/**
 * 认证结果类
 */
public class AuthResult {
    
    private boolean success;
    private String message;
    private String token;
    private User user;
    
    public AuthResult() {
    }
    
    /**
     * 创建成功结果
     */
    public static AuthResult success(String token, User user) {
        AuthResult result = new AuthResult();
        result.success = true;
        result.message = "操作成功";
        result.token = token;
        result.user = user;
        return result;
    }
    
    /**
     * 创建失败结果
     */
    public static AuthResult failure(String message) {
        AuthResult result = new AuthResult();
        result.success = false;
        result.message = message;
        return result;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
    
    public User getUser() {
        return user;
    }
    
    public void setUser(User user) {
        this.user = user;
    }
}
