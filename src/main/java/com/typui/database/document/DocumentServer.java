package com.typui.database.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typui.database.ConfigManager;
import com.typui.database.auth.AuthService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DocumentServer {
    
    private static final Logger logger = LoggerFactory.getLogger(DocumentServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ConfigManager configManager;
    private final File dataDir;
    private final Map<String, DocumentDatabase> databases;
    private final AuthService authService;
    private final com.typui.database.security.AuthMiddleware authMiddleware;
    private Javalin app;
    private boolean running;
    
    public DocumentServer(ConfigManager configManager, AuthService authService) {
        this.configManager = configManager;
        this.authService = authService;
        this.dataDir = new File(configManager.getDocumentDbDataDir());
        this.databases = new ConcurrentHashMap<>();
        this.running = false;

        String adminKey = configManager.getAdminPassword();
        this.authMiddleware = new com.typui.database.security.AuthMiddleware(
            dataDir.getAbsolutePath(),
            (adminKey != null && !adminKey.isEmpty()) ? adminKey : "admin123"
        );

        initializeDataDir();
    }
    
    private void initializeDataDir() {
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            logger.info("文档数据库目录创建成功: {}", dataDir.getAbsolutePath());
        }
        
        loadDatabases();
    }
    
    private void loadDatabases() {
        File[] dirs = dataDir.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                String dbName = dir.getName();
                databases.put(dbName, new DocumentDatabase(dbName, dataDir));
                logger.info("数据库加载成功: {}", dbName);
            }
        }
    }
    
    public void start() {
        if (running) {
            logger.warn("文档数据库服务器已在运行中");
            return;
        }
        
        int port = configManager.getDocumentDbPort();
        
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });
        
        setupRoutes();
        
        app.exception(Exception.class, (e, ctx) -> {
            logger.error("服务器异常: ", e);
            ctx.status(500).json(Map.of(
                "success", false,
                "error", "服务器内部错误: " + e.getMessage()
            ));
        });
        
        app.start(port);
        running = true;
        
        logger.info("========================================");
        logger.info("文档数据库服务器启动成功！");
        logger.info("端口: {}", port);
        logger.info("数据目录: {}", dataDir.getAbsolutePath());
        logger.info("========================================");
    }
    
    public void stop() {
        if (app != null) {
            app.stop();
            running = false;
            logger.info("文档数据库服务器已停止");
        }
    }
    
    private void setupRoutes() {
        // Restrict CORS — never respond with wildcard Allow-Origin/Allow-Headers.
        app.before(ctx -> {
            String origin = ctx.header("Origin");
            if (origin != null) {
                // Echo back the requesting origin (allows browser UI running on
                // localhost:8080 / 5500 etc.), rather than returning "*".
                ctx.header("Access-Control-Allow-Origin", origin);
                ctx.header("Vary", "Origin");
            }
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, X-API-Key, Authorization");
            ctx.header("Access-Control-Allow-Credentials", "true");
        });

        app.options("/*", ctx -> ctx.status(200).result());

        // 认证/权限检查 — 对非 public 路径执行 API 密钥验证
        if (configManager.isAuthEnabled()) {
            app.before(ctx -> {
                // 跳过 OPTIONS 预检请求（浏览器发送 OPTIONS 不携带自定义头）
                if ("OPTIONS".equalsIgnoreCase(ctx.method().name())) {
                    return;
                }
                io.javalin.http.Handler authHandler = authMiddleware.handle();
                try {
                    authHandler.handle(ctx);
                } catch (Exception e) {
                    logger.error("认证中间件异常", e);
                    int statusCode = ctx.status().getCode();
                    // 若中间件自身未设置 4xx/5xx 状态码，则统一返回 500
                    if (statusCode == 0 || (statusCode >= 200 && statusCode < 400)) {
                        ctx.status(500).json(Map.of(
                            "success", false,
                            "error", "认证检查失败"
                        ));
                    }
                }
            });
        } else {
            logger.warn("文档数据库认证已禁用 — 所有请求无需密钥。仅在本地部署环境使用此配置。");
        }

        app.get("/", this::handleRoot);
        app.get("/status", this::handleStatus);
        
        app.get("/databases", this::handleListDatabases);
        app.post("/databases", this::handleCreateDatabase);
        app.delete("/databases/{database}", this::handleDropDatabase);
        
        app.get("/{database}/collections", this::handleListCollections);
        app.post("/{database}/collections", this::handleCreateCollection);
        app.delete("/{database}/collections/{collection}", this::handleDropCollection);
        
        app.get("/{database}/{collection}", this::handleFindDocuments);
        app.post("/{database}/{collection}", this::handleInsertDocument);
        
        app.get("/{database}/{collection}/count", this::handleCount);
        app.post("/{database}/{collection}/count", this::handleCount);
        app.post("/{database}/{collection}/bulk", this::handleBulkInsert);
        app.post("/{database}/{collection}/query", this::handleQuery);
        app.post("/{database}/{collection}/update", this::handleUpdateMany);
        app.post("/{database}/{collection}/delete", this::handleDeleteMany);
        app.post("/{database}/{collection}/index", this::handleCreateIndex);
        app.post("/{database}/counters/incCounter", this::handleIncCounter);
        
        app.get("/{database}/{collection}/{id}", this::handleFindOneDocument);
        app.put("/{database}/{collection}/{id}", this::handleUpdateDocument);
        app.delete("/{database}/{collection}/{id}", this::handleDeleteDocument);
    }
    
    private DocumentDatabase getOrCreateDatabase(String dbName) {
        return databases.computeIfAbsent(dbName, 
            name -> new DocumentDatabase(name, dataDir));
    }
    
    private void handleRoot(Context ctx) {
        ctx.json(Map.of(
            "name", "TYPUI Document Database",
            "version", "3.4.2567.24",
            "type", "document",
            "port", configManager.getDocumentDbPort(),
            "databases", databases.size()
        ));
    }
    
    private void handleStatus(Context ctx) {
        ctx.json(Map.of(
            "success", true,
            "running", running,
            "databases", databases.size(),
            "dataDir", dataDir.getAbsolutePath()
        ));
    }
    
    private void handleListDatabases(Context ctx) {
        List<Map<String, Object>> dbList = new ArrayList<>();
        for (Map.Entry<String, DocumentDatabase> entry : databases.entrySet()) {
            dbList.add(Map.of(
                "name", entry.getKey(),
                "collections", entry.getValue().getCollectionNames().size()
            ));
        }
        ctx.json(Map.of("success", true, "databases", dbList));
    }
    
    private void handleCreateDatabase(Context ctx) {
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String dbName = (String) body.get("name");
            
            if (dbName == null || dbName.isEmpty()) {
                ctx.status(400).json(Map.of("success", false, "error", "数据库名称不能为空"));
                return;
            }
            
            if (databases.containsKey(dbName)) {
                ctx.status(400).json(Map.of("success", false, "error", "数据库已存在"));
                return;
            }
            
            databases.put(dbName, new DocumentDatabase(dbName, dataDir));
            ctx.json(Map.of("success", true, "message", "数据库创建成功", "name", dbName));
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void handleDropDatabase(Context ctx) {
        String dbName = ctx.pathParam("database");
        
        DocumentDatabase removed = databases.remove(dbName);
        if (removed != null) {
            File dbDir = new File(dataDir, dbName);
            deleteDirectory(dbDir);
            ctx.json(Map.of("success", true, "message", "数据库删除成功"));
        } else {
            ctx.status(404).json(Map.of("success", false, "error", "数据库不存在"));
        }
    }
    
    private void handleListCollections(Context ctx) {
        String dbName = ctx.pathParam("database");
        DocumentDatabase db = getOrCreateDatabase(dbName);
        
        ctx.json(Map.of("success", true, "collections", db.listCollections()));
    }
    
    private void handleCreateCollection(Context ctx) {
        String dbName = ctx.pathParam("database");
        DocumentDatabase db = getOrCreateDatabase(dbName);
        
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String collectionName = (String) body.get("name");
            
            if (collectionName == null || collectionName.isEmpty()) {
                ctx.status(400).json(Map.of("success", false, "error", "集合名称不能为空"));
                return;
            }
            
            db.createCollection(collectionName);
            ctx.json(Map.of("success", true, "message", "集合创建成功", "name", collectionName));
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void handleDropCollection(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        
        if (db.dropCollection(collectionName)) {
            ctx.json(Map.of("success", true, "message", "集合删除成功"));
        } else {
            ctx.status(404).json(Map.of("success", false, "error", "集合不存在"));
        }
    }
    
    @SuppressWarnings("unchecked")
    private void handleFindDocuments(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        String filterStr = ctx.queryParam("filter");
        List<Map<String, Object>> documents;
        
        if (filterStr != null && !filterStr.isEmpty()) {
            try {
                Map<String, Object> filter = objectMapper.readValue(filterStr, Map.class);
                documents = collection.find(filter);
            } catch (Exception e) {
                ctx.status(400).json(Map.of("success", false, "error", "无效的过滤条件: " + e.getMessage()));
                return;
            }
        } else {
            documents = collection.findAll();
        }
        
        ctx.json(Map.of("success", true, "documents", documents, "count", documents.size()));
    }
    
    private void handleFindOneDocument(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        String id = ctx.pathParam("id");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        Map<String, Object> document = collection.findById(id);
        
        if (document != null) {
            ctx.json(Map.of("success", true, "document", document));
        } else {
            ctx.status(404).json(Map.of("success", false, "error", "文档不存在"));
        }
    }
    
    private void handleInsertDocument(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        try {
            Map<String, Object> document = ctx.bodyAsClass(Map.class);
            Map<String, Object> inserted = collection.insert(document);
            
            ctx.status(201).json(Map.of("success", true, "document", inserted));
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void handleUpdateDocument(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        String id = ctx.pathParam("id");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        try {
            Map<String, Object> update = ctx.bodyAsClass(Map.class);
            Map<String, Object> updated = collection.updateById(id, update);
            
            if (updated != null) {
                ctx.json(Map.of("success", true, "document", updated));
            } else {
                ctx.status(404).json(Map.of("success", false, "error", "文档不存在"));
            }
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void handleDeleteDocument(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        String id = ctx.pathParam("id");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        if (collection.deleteById(id)) {
            ctx.json(Map.of("success", true, "message", "文档删除成功"));
        } else {
            ctx.status(404).json(Map.of("success", false, "error", "文档不存在"));
        }
    }
    
    private void handleBulkInsert(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> documents = (List<Map<String, Object>>) body.get("documents");
            
            if (documents == null || documents.isEmpty()) {
                ctx.status(400).json(Map.of("success", false, "error", "文档列表不能为空"));
                return;
            }
            
            List<Map<String, Object>> inserted = collection.insertMany(documents);
            ctx.status(201).json(Map.of("success", true, "documents", inserted, "count", inserted.size()));
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void handleQuery(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>) body.get("query");
            
            List<Map<String, Object>> documents = collection.find(query);
            ctx.json(Map.of("success", true, "documents", documents, "count", documents.size()));
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void handleUpdateMany(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>) body.get("query");
            @SuppressWarnings("unchecked")
            Map<String, Object> update = (Map<String, Object>) body.get("update");
            
            long count = collection.updateMany(query, update);
            ctx.json(Map.of("success", true, "modifiedCount", count));
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void handleDeleteMany(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>) body.get("query");
            
            long count = collection.deleteMany(query);
            ctx.json(Map.of("success", true, "deletedCount", count));
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void handleCount(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        long count;
        String method = ctx.method().name();
        
        if ("POST".equals(method)) {
            try {
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> query = (Map<String, Object>) body.get("query");
                count = collection.count(query);
            } catch (Exception e) {
                ctx.status(400).json(Map.of("success", false, "error", "无效的查询参数"));
                return;
            }
        } else {
            count = collection.count();
        }
        
        ctx.json(Map.of("success", true, "count", count));
    }
    
    private void handleCreateIndex(Context ctx) {
        String dbName = ctx.pathParam("database");
        String collectionName = ctx.pathParam("collection");
        
        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection collection = db.getCollection(collectionName);
        
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String field = (String) body.get("field");
            
            if (field == null || field.isEmpty()) {
                ctx.status(400).json(Map.of("success", false, "error", "字段名不能为空"));
                return;
            }
            
            collection.createIndex(field);
            ctx.json(Map.of("success", true, "message", "索引创建成功", "field", field));
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void handleIncCounter(Context ctx) {
        String dbName = ctx.pathParam("database");

        DocumentDatabase db = getOrCreateDatabase(dbName);
        DocumentCollection counters = db.getCollection("counters");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String field = (String) body.get("field");

            if (field == null || field.isEmpty()) {
                ctx.status(400).json(Map.of("success", false, "error", "字段名不能为空"));
                return;
            }

            // 使用原子化递增，避免并发请求之间的增量丢失
            long newValue = counters.atomicIncrement(field);
            ctx.json(Map.of("success", true, "value", newValue));

        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
    
    public boolean isRunning() {
        return running;
    }

    public Map<String, DocumentDatabase> getDatabases() {
        return databases;
    }

    public DocumentDatabase getDatabase(String name) {
        return databases.get(name);
    }
}
