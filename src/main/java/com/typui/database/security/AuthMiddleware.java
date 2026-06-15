package com.typui.database.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API权限认证中间件
 * 提供API Key认证和权限控制
 */
public class AuthMiddleware {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthMiddleware.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final File keysFile;
    private final Map<String, ApiKey> apiKeys;
    private final Set<String> publicPaths;
    private final String adminKey;
    
    public AuthMiddleware(String dataDir, String adminKey) {
        this.keysFile = new File(dataDir, ".api_keys.json");
        this.apiKeys = new ConcurrentHashMap<>();
        this.publicPaths = ConcurrentHashMap.newKeySet();
        this.adminKey = adminKey;
        
        initializePublicPaths();
        loadApiKeys();
    }
    
    /**
     * 初始化公开路径
     */
    private void initializePublicPaths() {
        publicPaths.add("/");
        publicPaths.add("/status");
        publicPaths.add("/ui");
        publicPaths.add("/categories");
        publicPaths.add("/types");
    }
    
    /**
     * 加载API密钥
     */
    @SuppressWarnings("unchecked")
    private void loadApiKeys() {
        if (keysFile.exists()) {
            try {
                Map<String, Object> data = objectMapper.readValue(keysFile, Map.class);
                List<Map<String, Object>> keysList = (List<Map<String, Object>>) data.get("keys");
                if (keysList != null) {
                    for (Map<String, Object> keyData : keysList) {
                        ApiKey key = ApiKey.fromMap(keyData);
                        apiKeys.put(key.getKey(), key);
                    }
                }
                logger.info("API密钥加载完成，共 {} 个", apiKeys.size());
            } catch (Exception e) {
                logger.error("加载API密钥失败", e);
            }
        }
        
        if (adminKey != null && !adminKey.isEmpty()) {
            ApiKey adminApiKey = new ApiKey();
            adminApiKey.setKey(adminKey);
            adminApiKey.setName("admin");
            adminApiKey.setRole("admin");
            adminApiKey.setPermissions(Arrays.asList("*"));
            adminApiKey.setCreatedAt(System.currentTimeMillis());
            adminApiKey.setActive(true);
            apiKeys.put(adminKey, adminApiKey);
        }
    }
    
    /**
     * 保存API密钥
     */
    private void saveApiKeys() {
        try {
            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> keysList = new ArrayList<>();
            for (ApiKey key : apiKeys.values()) {
                if (!key.getKey().equals(adminKey)) {
                    keysList.add(key.toMap());
                }
            }
            data.put("keys", keysList);
            
            keysFile.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(keysFile, data);
        } catch (Exception e) {
            logger.error("保存API密钥失败", e);
        }
    }
    
    /**
     * 创建API密钥
     */
    public ApiKey createApiKey(String name, String role, List<String> permissions, long expireTime) {
        String key = "typui_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        
        ApiKey apiKey = new ApiKey();
        apiKey.setKey(key);
        apiKey.setName(name);
        apiKey.setRole(role);
        apiKey.setPermissions(permissions);
        apiKey.setCreatedAt(System.currentTimeMillis());
        apiKey.setExpireAt(expireTime > 0 ? System.currentTimeMillis() + expireTime : 0);
        apiKey.setActive(true);
        
        apiKeys.put(key, apiKey);
        saveApiKeys();
        
        logger.info("API密钥创建成功: {} ({})", name, key);
        return apiKey;
    }
    
    /**
     * 删除API密钥
     */
    public boolean deleteApiKey(String key) {
        if (key.equals(adminKey)) {
            return false;
        }
        
        ApiKey removed = apiKeys.remove(key);
        if (removed != null) {
            saveApiKeys();
            logger.info("API密钥删除成功: {}", key);
            return true;
        }
        return false;
    }
    
    /**
     * 列出所有API密钥
     */
    public List<ApiKey> listApiKeys() {
        return new ArrayList<>(apiKeys.values());
    }
    
    /**
     * 验证权限
     */
    public boolean checkPermission(ApiKey apiKey, String method, String path) {
        if (apiKey == null || !apiKey.isActive()) {
            return false;
        }
        
        if (apiKey.getExpireAt() > 0 && System.currentTimeMillis() > apiKey.getExpireAt()) {
            return false;
        }
        
        if ("admin".equals(apiKey.getRole())) {
            return true;
        }
        
        List<String> permissions = apiKey.getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        
        if (permissions.contains("*")) {
            return true;
        }
        
        String requiredPermission = method.toUpperCase() + ":" + path;
        for (String permission : permissions) {
            if (matchPermission(permission, requiredPermission)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 匹配权限
     */
    private boolean matchPermission(String pattern, String permission) {
        if (pattern.equals(permission)) {
            return true;
        }
        
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return permission.startsWith(prefix);
        }
        
        return false;
    }
    
    /**
     * 认证中间件处理器
     */
    public Handler handle() {
        return ctx -> {
            String path = ctx.path();
            
            if (publicPaths.contains(path)) {
                return;
            }
            
            String apiKey = ctx.header("X-API-Key");
            if (apiKey == null) {
                apiKey = ctx.queryParam("api_key");
            }
            
            if (apiKey == null || apiKey.isEmpty()) {
                ctx.status(401).json(Map.of(
                    "success", false,
                    "error", "缺少API密钥"
                ));
                return;
            }
            
            ApiKey key = apiKeys.get(apiKey);
            if (key == null) {
                ctx.status(401).json(Map.of(
                    "success", false,
                    "error", "无效的API密钥"
                ));
                return;
            }
            
            if (!key.isActive()) {
                ctx.status(401).json(Map.of(
                    "success", false,
                    "error", "API密钥已禁用"
                ));
                return;
            }
            
            if (key.getExpireAt() > 0 && System.currentTimeMillis() > key.getExpireAt()) {
                ctx.status(401).json(Map.of(
                    "success", false,
                    "error", "API密钥已过期"
                ));
                return;
            }
            
            String method = ctx.method().name();
            if (!checkPermission(key, method, path)) {
                ctx.status(403).json(Map.of(
                    "success", false,
                    "error", "权限不足"
                ));
                return;
            }
            
            ctx.attribute("apiKey", key);
            ctx.attribute("apiKeyName", key.getName());
        };
    }
    
    /**
     * API密钥实体类
     */
    public static class ApiKey {
        private String key;
        private String name;
        private String role;
        private List<String> permissions;
        private long createdAt;
        private long expireAt;
        private boolean active;
        private long lastUsedAt;
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("key", key);
            map.put("name", name);
            map.put("role", role);
            map.put("permissions", permissions);
            map.put("createdAt", createdAt);
            map.put("expireAt", expireAt);
            map.put("active", active);
            map.put("lastUsedAt", lastUsedAt);
            return map;
        }
        
        public static ApiKey fromMap(Map<String, Object> map) {
            ApiKey apiKey = new ApiKey();
            apiKey.setKey((String) map.get("key"));
            apiKey.setName((String) map.get("name"));
            apiKey.setRole((String) map.get("role"));
            @SuppressWarnings("unchecked")
            List<String> perms = (List<String>) map.get("permissions");
            apiKey.setPermissions(perms);
            apiKey.setCreatedAt(map.get("createdAt") != null ? ((Number) map.get("createdAt")).longValue() : 0);
            apiKey.setExpireAt(map.get("expireAt") != null ? ((Number) map.get("expireAt")).longValue() : 0);
            apiKey.setActive(map.get("active") != null && Boolean.TRUE.equals(map.get("active")));
            apiKey.setLastUsedAt(map.get("lastUsedAt") != null ? ((Number) map.get("lastUsedAt")).longValue() : 0);
            return apiKey;
        }
        
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public List<String> getPermissions() { return permissions; }
        public void setPermissions(List<String> permissions) { this.permissions = permissions; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public long getExpireAt() { return expireAt; }
        public void setExpireAt(long expireAt) { this.expireAt = expireAt; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public long getLastUsedAt() { return lastUsedAt; }
        public void setLastUsedAt(long lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    }
}
