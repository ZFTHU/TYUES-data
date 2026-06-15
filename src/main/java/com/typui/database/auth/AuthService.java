package com.typui.database.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户认证服务
 */
public class AuthService {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .configure(SerializationFeature.INDENT_OUTPUT, false);
    
    private final File usersFile;
    private final Map<String, User> users;
    private final Map<String, Session> sessions;
    private final String secretKey;
    private final int tokenExpireHours;
    private final String adminUsername;
    private final String adminPassword;
    
    public AuthService(String dataDir, String secretKey, int tokenExpireHours, 
                       String adminUsername, String adminPassword) {
        this.usersFile = new File(dataDir, ".users.json");
        this.users = new ConcurrentHashMap<>();
        this.sessions = new ConcurrentHashMap<>();
        this.secretKey = secretKey;
        this.tokenExpireHours = tokenExpireHours;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        
        initializeUsers();
    }
    
    @SuppressWarnings("unchecked")
    private void initializeUsers() {
        if (usersFile.exists()) {
            try {
                Map<String, Object> data = objectMapper.readValue(usersFile, Map.class);
                List<Map<String, Object>> usersList = (List<Map<String, Object>>) data.get("users");
                if (usersList != null) {
                    for (Map<String, Object> userData : usersList) {
                        User user = User.fromMap(userData);
                        users.put(user.getUsername(), user);
                    }
                }
                logger.info("用户数据加载完成，共 {} 个用户", users.size());
            } catch (IOException e) {
                logger.error("用户数据加载失败", e);
                createDefaultAdmin();
            }
        } else {
            createDefaultAdmin();
        }
    }
    
    private void createDefaultAdmin() {
        User admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setUsername(adminUsername);
        admin.setPassword(BCrypt.hashpw(adminPassword, BCrypt.gensalt()));
        admin.setRole("admin");
        admin.setCreatedAt(System.currentTimeMillis());
        admin.setUpdatedAt(System.currentTimeMillis());
        
        users.put(adminUsername, admin);
        saveUsers();
        
        logger.info("默认管理员创建成功: {}", adminUsername);
    }
    
    private void saveUsers() {
        try {
            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> usersList = new ArrayList<>();
            for (User user : users.values()) {
                usersList.add(user.toMap());
            }
            data.put("users", usersList);
            
            usersFile.getParentFile().mkdirs();
            objectMapper.writeValue(usersFile, data);
        } catch (IOException e) {
            logger.error("保存用户数据失败", e);
        }
    }
    
    public AuthResult register(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return AuthResult.failure("用户名不能为空");
        }
        
        if (password == null || password.length() < 6) {
            return AuthResult.failure("密码长度至少6位");
        }
        
        if (users.containsKey(username)) {
            return AuthResult.failure("用户名已存在");
        }
        
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setRole("user");
        user.setCreatedAt(System.currentTimeMillis());
        user.setUpdatedAt(System.currentTimeMillis());
        
        users.put(username, user);
        saveUsers();
        
        String token = generateToken(user);
        Session session = createSession(user, token);
        sessions.put(token, session);
        
        logger.info("用户注册成功: {}", username);
        
        return AuthResult.success(token, user);
    }
    
    public AuthResult login(String username, String password) {
        if (username == null || password == null) {
            return AuthResult.failure("用户名或密码不能为空");
        }
        
        User user = users.get(username);
        if (user == null) {
            return AuthResult.failure("用户名或密码错误");
        }
        
        if (!BCrypt.checkpw(password, user.getPassword())) {
            return AuthResult.failure("用户名或密码错误");
        }
        
        String token = generateToken(user);
        Session session = createSession(user, token);
        sessions.put(token, session);
        
        user.setLastLoginAt(System.currentTimeMillis());
        saveUsers();
        
        logger.info("用户登录成功: {}", username);
        
        return AuthResult.success(token, user);
    }
    
    public boolean logout(String token) {
        Session session = sessions.remove(token);
        if (session != null) {
            logger.info("用户登出成功: {}", session.getUsername());
            return true;
        }
        return false;
    }
    
    public User validateToken(String token) {
        if (token == null) {
            return null;
        }
        
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        
        if (session.isExpired()) {
            sessions.remove(token);
            return null;
        }
        
        return users.get(session.getUsername());
    }
    
    public boolean changePassword(String username, String oldPassword, String newPassword) {
        User user = users.get(username);
        if (user == null) {
            return false;
        }
        
        if (!BCrypt.checkpw(oldPassword, user.getPassword())) {
            return false;
        }
        
        user.setPassword(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        user.setUpdatedAt(System.currentTimeMillis());
        saveUsers();
        
        logger.info("密码修改成功: {}", username);
        return true;
    }
    
    public boolean deleteUser(String username) {
        if (username.equals(adminUsername)) {
            return false;
        }
        
        User removed = users.remove(username);
        if (removed != null) {
            sessions.values().removeIf(s -> s.getUsername().equals(username));
            saveUsers();
            logger.info("用户删除成功: {}", username);
            return true;
        }
        return false;
    }
    
    public List<User> getAllUsers() {
        return new ArrayList<>(users.values());
    }
    
    public User getUser(String username) {
        return users.get(username);
    }
    
    private String generateToken(User user) {
        String raw = user.getId() + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
        return Base64.getEncoder().encodeToString(raw.getBytes());
    }
    
    private Session createSession(User user, String token) {
        Session session = new Session();
        session.setToken(token);
        session.setUsername(user.getUsername());
        session.setCreatedAt(System.currentTimeMillis());
        session.setExpiresAt(System.currentTimeMillis() + (tokenExpireHours * 60 * 60 * 1000L));
        return session;
    }
    
    public void cleanExpiredSessions() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
}
