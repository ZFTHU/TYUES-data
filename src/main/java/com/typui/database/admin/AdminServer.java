package com.typui.database.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typui.database.ConfigManager;
import com.typui.database.Main;
import com.typui.database.document.DocumentCollection;
import com.typui.database.document.DocumentDatabase;
import com.typui.database.document.DocumentServer;
import com.typui.database.error.ErrorCode;
import com.typui.database.error.ErrorHandler;
import com.typui.database.logging.LogManager;
import com.typui.database.storage.StorageServer;
import com.typui.database.util.StartupBanner;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class AdminServer {
    
    private static final Logger logger = LoggerFactory.getLogger(AdminServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ConfigManager configManager;
    private final DocumentServer documentServer;
    private final StorageServer storageServer;
    private Javalin app;
    private boolean running;
    private final long startTime;
    
    public AdminServer(ConfigManager configManager, DocumentServer documentServer, StorageServer storageServer) {
        this.configManager = configManager;
        this.documentServer = documentServer;
        this.storageServer = storageServer;
        this.running = false;
        this.startTime = System.currentTimeMillis();
    }
    
    public void start() {
        if (running) return;
        
        int port = configManager.getFileStoragePort() + 1;
        
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            
            File webDir = new File("web");
            if (webDir.exists() && webDir.isDirectory()) {
                config.staticFiles.add("/web", Location.EXTERNAL);
            }
        });
        
        setupRoutes();
        
        app.exception(Exception.class, (e, ctx) -> {
            ErrorHandler.handle(e, ctx);
        });
        
        app.start(port);
        running = true;
        
        logger.info("Admin UI started: http://localhost:{}", port);
    }
    
    public void stop() {
        if (app != null) {
            app.stop();
            running = false;
        }
    }
    
    private void setupRoutes() {
        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
            ctx.header("Access-Control-Max-Age", "3600");
        });
        
        app.options("/*", ctx -> ctx.status(200).result(""));
        
        app.get("/", this::serveIndex);
        app.get("/api/status", this::handleStatus);
        
        app.get("/api/databases", this::handleListDatabases);
        app.get("/api/databases/{db}", this::handleDatabaseInfo);
        app.get("/api/databases/{db}/collections", this::handleListCollections);
        app.get("/api/databases/{db}/collections/{col}/documents", this::handleListDocuments);
        app.get("/api/databases/{db}/collections/{col}/documents/{id}", this::handleGetDocument);
        app.post("/api/databases/{db}/collections/{col}/documents", this::handleCreateDocument);
        app.put("/api/databases/{db}/collections/{col}/documents/{id}", this::handleUpdateDocument);
        app.delete("/api/databases/{db}/collections/{col}/documents/{id}", this::handleDeleteDocument);
        app.delete("/api/databases/{db}/collections/{col}", this::handleDropCollection);
        app.delete("/api/databases/{db}", this::handleDropDatabase);
        
        app.get("/api/buckets", this::handleListBucketsProxy);
        app.get("/api/buckets/{bucket}/files", this::handleListFilesProxy);
        app.delete("/api/buckets/{bucket}/files/{file}", this::handleDeleteFileProxy);
        
        app.get("/api/config", this::handleGetConfig);
        app.post("/api/config", this::handleUpdateConfig);
        app.put("/api/config", this::handleUpdateConfig);
        
        app.post("/api/databases/{db}/collections", this::handleCreateCollection);
        app.post("/api/query", this::handleQuery);
        app.post("/api/batch-update", this::handleBatchUpdate);
        
        app.get("/api/system/info", this::handleSystemInfo);
        app.get("/api/system/stats", this::handleSystemStats);
        app.get("/api/system/health", this::handleHealthCheck);
        
        app.get("/api/logs", this::handleGetLogs);
        app.get("/api/logs/stats", this::handleLogStats);
        app.post("/api/logs/clear", this::handleClearLogs);
        app.post("/api/logs/compress", this::handleCompressLogs);
        
        app.get("/api/version", this::handleVersion);
    }
    
    private void serveIndex(Context ctx) {
        File webDir = new File("web");
        File indexFile = new File(webDir, "index.html");
        
        if (indexFile.exists()) {
            try {
                String content = Files.readString(indexFile.toPath());
                ctx.html(content);
            } catch (IOException e) {
                ctx.html(getFallbackHtml());
            }
        } else {
            ctx.html(getFallbackHtml());
        }
    }
    
    private void handleStatus(Context ctx) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("success", true);
        status.put("databaseName", configManager.getDatabaseName());
        status.put("documentServer", documentServer.isRunning());
        status.put("storageServer", storageServer.isRunning());
        status.put("encryptionEnabled", configManager.isEncryptionEnabled());
        status.put("documentPort", configManager.getDocumentDbPort());
        status.put("storagePort", configManager.getFileStoragePort());
        status.put("adminPort", configManager.getFileStoragePort() + 1);
        status.put("uptime", System.currentTimeMillis() - startTime);
        
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir);
        int dbCount = 0;
        int colCount = 0;
        int docCount = 0;
        long totalSize = 0;
        
        if (dbPath.exists()) {
            File[] databases = dbPath.listFiles(File::isDirectory);
            if (databases != null) {
                dbCount = databases.length;
                for (File db : databases) {
                    File[] collections = db.listFiles(File::isDirectory);
                    if (collections != null) {
                        colCount += collections.length;
                        for (File col : collections) {
                            File[] docs = col.listFiles((d, n) -> n.endsWith(".json"));
                            if (docs != null) {
                                docCount += docs.length;
                                for (File doc : docs) {
                                    totalSize += doc.length();
                                }
                            }
                        }
                    }
                }
            }
        }
        
        status.put("databases", dbCount);
        status.put("collections", colCount);
        status.put("documents", docCount);
        status.put("dataSize", formatSize(totalSize));
        
        ctx.json(status);
    }
    
    private void handleSystemInfo(Context ctx) {
        Map<String, Object> info = new LinkedHashMap<>();
        
        Runtime runtime = Runtime.getRuntime();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        
        info.put("version", StartupBanner.getVersion());
        info.put("buildDate", StartupBanner.getVersionInfo().get("buildDate"));
        info.put("java", Map.of(
            "version", System.getProperty("java.version"),
            "vendor", System.getProperty("java.vendor"),
            "home", System.getProperty("java.home")
        ));
        info.put("os", Map.of(
            "name", System.getProperty("os.name"),
            "version", System.getProperty("os.version"),
            "arch", System.getProperty("os.arch")
        ));
        info.put("runtime", Map.of(
            "processors", runtime.availableProcessors(),
            "maxMemory", formatSize(runtime.maxMemory()),
            "totalMemory", formatSize(runtime.totalMemory()),
            "freeMemory", formatSize(runtime.freeMemory()),
            "usedMemory", formatSize(runtime.totalMemory() - runtime.freeMemory())
        ));
        info.put("uptime", formatUptime(System.currentTimeMillis() - startTime));
        info.put("startTime", startTime);
        info.put("pid", getCurrentPid());
        
        ctx.json(Map.of("success", true, "system", info));
    }
    
    private void handleSystemStats(Context ctx) {
        Map<String, Object> stats = new LinkedHashMap<>();
        
        Runtime runtime = Runtime.getRuntime();
        
        stats.put("memory", Map.of(
            "max", runtime.maxMemory(),
            "total", runtime.totalMemory(),
            "free", runtime.freeMemory(),
            "used", runtime.totalMemory() - runtime.freeMemory(),
            "usagePercent", (runtime.totalMemory() - runtime.freeMemory()) * 100.0 / runtime.maxMemory()
        ));
        
        stats.put("threads", Map.of(
            "active", Thread.activeCount(),
            "peak", ManagementFactory.getThreadMXBean().getPeakThreadCount(),
            "total", ManagementFactory.getThreadMXBean().getTotalStartedThreadCount()
        ));
        
        stats.put("processors", runtime.availableProcessors());
        
        if (LogManager.getInstance() != null) {
            stats.put("logs", LogManager.getInstance().getLogStats());
        }
        
        stats.put("uptime", System.currentTimeMillis() - startTime);
        stats.put("uptimeFormatted", formatUptime(System.currentTimeMillis() - startTime));
        
        ctx.json(Map.of("success", true, "stats", stats));
    }
    
    private void handleHealthCheck(Context ctx) {
        Map<String, Object> health = new LinkedHashMap<>();
        
        boolean docHealthy = documentServer != null && documentServer.isRunning();
        boolean storageHealthy = storageServer != null && storageServer.isRunning();
        boolean allHealthy = docHealthy && storageHealthy;
        
        health.put("status", allHealthy ? "UP" : "DOWN");
        health.put("timestamp", System.currentTimeMillis());
        health.put("components", Map.of(
            "documentServer", Map.of("status", docHealthy ? "UP" : "DOWN", "port", configManager.getDocumentDbPort()),
            "storageServer", Map.of("status", storageHealthy ? "UP" : "DOWN", "port", configManager.getFileStoragePort()),
            "adminServer", Map.of("status", running ? "UP" : "DOWN", "port", configManager.getFileStoragePort() + 1)
        ));
        
        ctx.status(allHealthy ? 200 : 503).json(health);
    }
    
    private void handleGetLogs(Context ctx) {
        String level = ctx.queryParam("level");
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        
        if (LogManager.getInstance() == null) {
            ctx.json(Map.of("success", true, "logs", Collections.emptyList()));
            return;
        }
        
        List<LogManager.LogEntry> logs = LogManager.getInstance().getRecentLogs(limit, level);
        
        List<Map<String, Object>> logList = new ArrayList<>();
        for (LogManager.LogEntry entry : logs) {
            logList.add(entry.toMap());
        }
        
        ctx.json(Map.of("success", true, "logs", logList, "count", logList.size()));
    }
    
    private void handleLogStats(Context ctx) {
        if (LogManager.getInstance() == null) {
            ctx.json(Map.of("success", true, "stats", Collections.emptyMap()));
            return;
        }
        
        ctx.json(Map.of("success", true, "stats", LogManager.getInstance().getLogStats()));
    }
    
    private void handleClearLogs(Context ctx) {
        if (LogManager.getInstance() != null) {
            LogManager.getInstance().forceClean();
        }
        ctx.json(ErrorCode.success("日志已清理"));
    }
    
    private void handleCompressLogs(Context ctx) {
        if (LogManager.getInstance() != null) {
            LogManager.getInstance().forceCompress();
        }
        ctx.json(ErrorCode.success("日志压缩完成"));
    }
    
    private void handleVersion(Context ctx) {
        ctx.json(Map.of("success", true, "version", StartupBanner.getVersionInfo()));
    }
    
    private void handleListDatabases(Context ctx) {
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir);
        
        List<Map<String, Object>> databases = new ArrayList<>();
        
        if (dbPath.exists() && dbPath.isDirectory()) {
            File[] dirs = dbPath.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File dir : dirs) {
                    Map<String, Object> dbInfo = new LinkedHashMap<>();
                    dbInfo.put("name", dir.getName());
                    dbInfo.put("size", formatSize(calculateDirSize(dir)));
                    
                    File[] collections = dir.listFiles(File::isDirectory);
                    dbInfo.put("collections", collections != null ? collections.length : 0);
                    
                    databases.add(dbInfo);
                }
            }
        }
        
        ctx.json(Map.of("success", true, "databases", databases));
    }
    
    private void handleDatabaseInfo(Context ctx) {
        String dbName = ctx.pathParam("db");
        
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir, dbName);
        
        if (!dbPath.exists()) {
            ctx.status(404).json(ErrorCode.error(ErrorCode.DB_NOT_FOUND));
            return;
        }
        
        List<Map<String, Object>> collections = new ArrayList<>();
        File[] colDirs = dbPath.listFiles(File::isDirectory);
        if (colDirs != null) {
            for (File col : colDirs) {
                File[] docs = col.listFiles((d, n) -> n.endsWith(".json"));
                collections.add(Map.of(
                    "name", col.getName(),
                    "count", docs != null ? docs.length : 0
                ));
            }
        }
        
        ctx.json(Map.of(
            "success", true,
            "database", Map.of(
                "name", dbName,
                "collections", collections,
                "size", formatSize(calculateDirSize(dbPath))
            )
        ));
    }
    
    private void handleListCollections(Context ctx) {
        String dbName = ctx.pathParam("db");
        
        DocumentDatabase db = documentServer.getDatabase(dbName);
        if (db == null) {
            ctx.json(Map.of("success", true, "collections", Collections.emptyList()));
            return;
        }
        
        List<Map<String, Object>> collections = new ArrayList<>();
        for (String colName : db.listCollections()) {
            DocumentCollection col = db.getCollection(colName);
            Map<String, Object> colInfo = new LinkedHashMap<>();
            colInfo.put("name", colName);
            colInfo.put("count", col.count());
            collections.add(colInfo);
        }
        
        ctx.json(Map.of("success", true, "collections", collections));
    }
    
    private void handleListDocuments(Context ctx) {
        String dbName = ctx.pathParam("db");
        String colName = ctx.pathParam("col");
        String pageStr = ctx.queryParam("page");
        String limitStr = ctx.queryParam("limit");
        int page = pageStr != null ? Integer.parseInt(pageStr) : 1;
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 50;
        
        DocumentDatabase db = documentServer.getDatabase(dbName);
        if (db == null) {
            ctx.json(Map.of("success", true, "documents", Collections.emptyList(), "total", 0));
            return;
        }
        
        DocumentCollection col = db.getCollection(colName);
        List<Map<String, Object>> allDocs = col.findAll();
        int total = allDocs.size();
        
        int fromIndex = (page - 1) * limit;
        int toIndex = Math.min(fromIndex + limit, total);
        
        List<Map<String, Object>> docs;
        if (fromIndex < total) {
            docs = allDocs.subList(fromIndex, toIndex);
        } else {
            docs = Collections.emptyList();
        }
        
        ctx.json(Map.of("success", true, "documents", docs, "total", total, "page", page, "limit", limit));
    }
    
    private void handleGetDocument(Context ctx) {
        String dbName = ctx.pathParam("db");
        String colName = ctx.pathParam("col");
        String id = ctx.pathParam("id");
        
        DocumentDatabase db = documentServer.getDatabase(dbName);
        if (db == null) {
            ctx.status(404).json(ErrorCode.error(ErrorCode.DB_NOT_FOUND));
            return;
        }
        
        DocumentCollection col = db.getCollection(colName);
        Map<String, Object> doc = col.findById(id);
        
        if (doc != null) {
            ctx.json(Map.of("success", true, "document", doc));
        } else {
            ctx.status(404).json(ErrorCode.error(ErrorCode.DOC_NOT_FOUND));
        }
    }
    
    private void handleCreateDocument(Context ctx) {
        String dbName = ctx.pathParam("db");
        String colName = ctx.pathParam("col");
        
        try {
            Map<String, Object> doc = ctx.bodyAsClass(Map.class);
            
            DocumentDatabase db = documentServer.getDatabase(dbName);
            if (db == null) {
                ctx.status(404).json(ErrorCode.error(ErrorCode.DB_NOT_FOUND));
                return;
            }
            
            DocumentCollection col = db.getCollection(colName);
            Map<String, Object> inserted = col.insert(doc);
            
            ctx.json(Map.of("success", true, "document", inserted));
        } catch (Exception e) {
            ctx.status(500).json(ErrorCode.error(ErrorCode.DOC_INSERT_FAILED, e));
        }
    }
    
    private void handleUpdateDocument(Context ctx) {
        String dbName = ctx.pathParam("db");
        String colName = ctx.pathParam("col");
        String id = ctx.pathParam("id");
        
        try {
            Map<String, Object> update = ctx.bodyAsClass(Map.class);
            
            DocumentDatabase db = documentServer.getDatabase(dbName);
            if (db == null) {
                ctx.status(404).json(ErrorCode.error(ErrorCode.DB_NOT_FOUND));
                return;
            }
            
            DocumentCollection col = db.getCollection(colName);
            Map<String, Object> updated = col.updateById(id, update);
            
            if (updated != null) {
                ctx.json(Map.of("success", true, "document", updated));
            } else {
                ctx.status(404).json(ErrorCode.error(ErrorCode.DOC_NOT_FOUND));
            }
        } catch (Exception e) {
            ctx.status(500).json(ErrorCode.error(ErrorCode.DOC_UPDATE_FAILED, e));
        }
    }
    
    private void handleDeleteDocument(Context ctx) {
        String dbName = ctx.pathParam("db");
        String colName = ctx.pathParam("col");
        String id = ctx.pathParam("id");
        
        DocumentDatabase db = documentServer.getDatabase(dbName);
        if (db == null) {
            ctx.status(404).json(ErrorCode.error(ErrorCode.DB_NOT_FOUND));
            return;
        }
        
        DocumentCollection col = db.getCollection(colName);
        if (col.deleteById(id)) {
            ctx.json(ErrorCode.success());
        } else {
            ctx.status(404).json(ErrorCode.error(ErrorCode.DOC_NOT_FOUND));
        }
    }
    
    private void handleDropCollection(Context ctx) {
        String dbName = ctx.pathParam("db");
        String colName = ctx.pathParam("col");
        
        DocumentDatabase db = documentServer.getDatabase(dbName);
        if (db != null && db.dropCollection(colName)) {
            ctx.json(ErrorCode.success());
        } else {
            ctx.status(404).json(ErrorCode.error(ErrorCode.COLLECTION_NOT_FOUND));
        }
    }
    
    private void handleDropDatabase(Context ctx) {
        String dbName = ctx.pathParam("db");
        
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir, dbName);
        
        if (dbPath.exists()) {
            deleteDirectory(dbPath);
            ctx.json(ErrorCode.success());
        } else {
            ctx.status(404).json(ErrorCode.error(ErrorCode.DB_NOT_FOUND));
        }
    }
    
    private void handleCreateCollection(Context ctx) {
        String dbName = ctx.pathParam("db");
        
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String colName = (String) body.get("name");
            
            if (colName == null || colName.isEmpty()) {
                ctx.status(400).json(ErrorCode.error(ErrorCode.COLLECTION_NAME_EMPTY));
                return;
            }
            
            DocumentDatabase db = documentServer.getDatabase(dbName);
            if (db == null) {
                ctx.status(404).json(ErrorCode.error(ErrorCode.DB_NOT_FOUND));
                return;
            }
            
            db.getCollection(colName);
            ctx.json(Map.of("success", true, "message", "集合创建成功", "name", colName));
        } catch (Exception e) {
            ctx.status(500).json(ErrorCode.error(ErrorCode.COLLECTION_CREATE_FAILED, e));
        }
    }
    
    private void handleQuery(Context ctx) {
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String dbName = (String) body.get("database");
            String colName = (String) body.get("collection");
            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) body.get("filter");
            
            DocumentDatabase db = documentServer.getDatabase(dbName);
            if (db == null) {
                ctx.json(Map.of("success", true, "results", Collections.emptyList()));
                return;
            }
            
            DocumentCollection col = db.getCollection(colName);
            List<Map<String, Object>> results = col.findAll();
            
            if (filter != null && !filter.isEmpty()) {
                results = filterResults(results, filter);
            }
            
            ctx.json(Map.of("success", true, "results", results, "count", results.size()));
        } catch (Exception e) {
            ctx.status(500).json(ErrorCode.error(ErrorCode.QUERY_FAILED, e));
        }
    }
    
    private void handleBatchUpdate(Context ctx) {
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String dbName = (String) body.get("database");
            String colName = (String) body.get("collection");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> documents = (List<Map<String, Object>>) body.get("documents");
            
            if (documents == null || documents.isEmpty()) {
                ctx.status(400).json(ErrorCode.error(ErrorCode.INVALID_PARAMETER, "文档列表不能为空"));
                return;
            }
            
            DocumentDatabase db = documentServer.getDatabase(dbName);
            if (db == null) {
                ctx.status(404).json(ErrorCode.error(ErrorCode.DB_NOT_FOUND));
                return;
            }
            
            DocumentCollection col = db.getCollection(colName);
            int updated = 0;
            
            for (Map<String, Object> doc : documents) {
                String id = (String) doc.get("_id");
                if (id != null) {
                    if (col.updateById(id, doc) != null) {
                        updated++;
                    }
                }
            }
            
            ctx.json(Map.of("success", true, "updated", updated, "total", documents.size()));
        } catch (Exception e) {
            ctx.status(500).json(ErrorCode.error(ErrorCode.DOC_UPDATE_FAILED, e));
        }
    }
    
    private List<Map<String, Object>> filterResults(List<Map<String, Object>> results, Map<String, Object> filter) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        
        for (Map<String, Object> doc : results) {
            boolean match = true;
            for (Map.Entry<String, Object> entry : filter.entrySet()) {
                Object docValue = doc.get(entry.getKey());
                Object filterValue = entry.getValue();
                
                if (filterValue instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ops = (Map<String, Object>) filterValue;
                    for (Map.Entry<String, Object> op : ops.entrySet()) {
                        String opKey = op.getKey();
                        Object opValue = op.getValue();
                        
                        switch (opKey) {
                            case "$eq":
                                match = Objects.equals(docValue, opValue);
                                break;
                            case "$ne":
                                match = !Objects.equals(docValue, opValue);
                                break;
                            case "$gt":
                                if (docValue instanceof Number && opValue instanceof Number) {
                                    match = ((Number) docValue).doubleValue() > ((Number) opValue).doubleValue();
                                }
                                break;
                            case "$lt":
                                if (docValue instanceof Number && opValue instanceof Number) {
                                    match = ((Number) docValue).doubleValue() < ((Number) opValue).doubleValue();
                                }
                                break;
                            case "$regex":
                                if (docValue != null && opValue != null) {
                                    match = docValue.toString().matches(opValue.toString());
                                }
                                break;
                        }
                        if (!match) break;
                    }
                } else {
                    match = Objects.equals(docValue, filterValue);
                }
                
                if (!match) break;
            }
            
            if (match) {
                filtered.add(doc);
            }
        }
        
        return filtered;
    }
    
    private void handleListBucketsProxy(Context ctx) {
        ctx.json(Map.of("success", true, "buckets", Collections.emptyList()));
    }
    
    private void handleListFilesProxy(Context ctx) {
        ctx.json(Map.of("success", true, "files", Collections.emptyList()));
    }
    
    private void handleDeleteFileProxy(Context ctx) {
        ctx.json(ErrorCode.success());
    }
    
    private void handleGetConfig(Context ctx) {
        Map<String, Object> config = new LinkedHashMap<>(configManager.getConfig());
        ctx.json(Map.of("success", true, "config", config));
    }
    
    private void handleUpdateConfig(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> newConfig = ctx.bodyAsClass(Map.class);
            configManager.updateConfig(newConfig);
            ctx.json(ErrorCode.success("配置已更新，部分设置需要重启生效"));
        } catch (Exception e) {
            ctx.status(500).json(ErrorCode.error(ErrorCode.CONFIG_SAVE_FAILED, e));
        }
    }
    
    private long calculateDirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else {
                    size += calculateDirSize(file);
                }
            }
        }
        return size;
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private String formatUptime(long ms) {
        long seconds = ms / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(secs).append("s");
        
        return sb.toString();
    }
    
    private String getCurrentPid() {
        return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
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
    
    private String getFallbackHtml() {
        return generateEnhancedAdminHtml();
    }
    
    private String generateEnhancedAdminHtml() {
        return "<!DOCTYPE html>\n" +
"<html lang=\"zh-CN\">\n" +
"<head>\n" +
"    <meta charset=\"UTF-8\">\n" +
"    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
"    <title>TYPUI Database Manager v2.1</title>\n" +
"    <style>\n" +
"        :root {\n" +
"            --primary: #6366f1;\n" +
"            --primary-light: #818cf8;\n" +
"            --primary-dark: #4f46e5;\n" +
"            --accent: #06b6d4;\n" +
"            --success: #22c55e;\n" +
"            --warning: #eab308;\n" +
"            --danger: #ef4444;\n" +
"            --bg-dark: #0c0c0f;\n" +
"            --bg-card: rgba(17, 17, 21, 0.95);\n" +
"            --bg-hover: rgba(99, 102, 241, 0.1);\n" +
"            --text-bright: #ffffff;\n" +
"            --text-normal: #e4e4e7;\n" +
"            --text-muted: #71717a;\n" +
"            --border-subtle: rgba(255, 255, 255, 0.06);\n" +
"            --border-light: rgba(255, 255, 255, 0.1);\n" +
"        }\n" +
"        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
"        body {\n" +
"            font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;\n" +
"            background: var(--bg-dark);\n" +
"            min-height: 100vh;\n" +
"            color: var(--text-normal);\n" +
"            overflow-x: hidden;\n" +
"        }\n" +
"        body::before {\n" +
"            content: '';\n" +
"            position: fixed;\n" +
"            top: 0; left: 0; right: 0; bottom: 0;\n" +
"            background: \n" +
"                radial-gradient(ellipse 80% 50% at 20% -20%, rgba(99, 102, 241, 0.15), transparent),\n" +
"                radial-gradient(ellipse 60% 40% at 80% 100%, rgba(6, 182, 212, 0.1), transparent);\n" +
"            pointer-events: none;\n" +
"            z-index: 0;\n" +
"        }\n" +
"        .app { display: flex; min-height: 100vh; position: relative; z-index: 1; }\n" +
"        .sidebar {\n" +
"            width: 260px;\n" +
"            background: var(--bg-card);\n" +
"            backdrop-filter: blur(20px);\n" +
"            border-right: 1px solid var(--border-subtle);\n" +
"            position: fixed;\n" +
"            height: 100vh;\n" +
"            display: flex;\n" +
"            flex-direction: column;\n" +
"            z-index: 100;\n" +
"        }\n" +
"        .sidebar-header { padding: 24px 20px; border-bottom: 1px solid var(--border-subtle); }\n" +
"        .logo { display: flex; align-items: center; gap: 12px; }\n" +
"        .logo-icon {\n" +
"            width: 44px; height: 44px;\n" +
"            background: linear-gradient(135deg, var(--primary), var(--accent));\n" +
"            border-radius: 12px;\n" +
"            display: flex; align-items: center; justify-content: center;\n" +
"            font-size: 16px; font-weight: 800; color: white;\n" +
"        }\n" +
"        .logo-text { font-size: 18px; font-weight: 700; color: var(--text-bright); }\n" +
"        .logo-sub { font-size: 10px; color: var(--text-muted); margin-top: 2px; }\n" +
"        .sidebar-nav { flex: 1; padding: 16px 12px; overflow-y: auto; }\n" +
"        .nav-section { margin-bottom: 24px; }\n" +
"        .nav-section-title {\n" +
"            font-size: 10px; font-weight: 700; color: var(--text-muted);\n" +
"            text-transform: uppercase; letter-spacing: 1.5px;\n" +
"            padding: 0 12px; margin-bottom: 8px;\n" +
"        }\n" +
"        .nav-item {\n" +
"            display: flex; align-items: center; gap: 12px;\n" +
"            padding: 12px 14px;\n" +
"            border-radius: 10px;\n" +
"            cursor: pointer;\n" +
"            transition: all 0.2s ease;\n" +
"            color: var(--text-muted);\n" +
"            margin-bottom: 2px;\n" +
"        }\n" +
"        .nav-item:hover { background: var(--bg-hover); color: var(--text-normal); }\n" +
"        .nav-item.active {\n" +
"            background: linear-gradient(135deg, rgba(99, 102, 241, 0.15), rgba(6, 182, 212, 0.1));\n" +
"            color: var(--text-bright);\n" +
"        }\n" +
"        .nav-icon { font-size: 16px; width: 20px; text-align: center; }\n" +
"        .sidebar-footer { padding: 16px; border-top: 1px solid var(--border-subtle); }\n" +
"        .db-info {\n" +
"            background: linear-gradient(135deg, rgba(99, 102, 241, 0.1), rgba(6, 182, 212, 0.05));\n" +
"            border: 1px solid rgba(99, 102, 241, 0.2);\n" +
"            border-radius: 10px; padding: 12px;\n" +
"        }\n" +
"        .db-label { font-size: 10px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 1px; margin-bottom: 4px; }\n" +
"        .db-name { font-size: 14px; font-weight: 600; color: var(--primary-light); }\n" +
"        .main { flex: 1; margin-left: 260px; padding: 24px; max-width: calc(100% - 260px); }\n" +
"        .page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }\n" +
"        .page-title { font-size: 24px; font-weight: 700; color: var(--text-bright); }\n" +
"        .card {\n" +
"            background: var(--bg-card);\n" +
"            backdrop-filter: blur(20px);\n" +
"            border: 1px solid var(--border-subtle);\n" +
"            border-radius: 16px;\n" +
"            padding: 24px;\n" +
"            margin-bottom: 20px;\n" +
"        }\n" +
"        .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }\n" +
"        .card-title { font-size: 15px; font-weight: 600; color: var(--text-bright); }\n" +
"        .stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 20px; }\n" +
"        @media (max-width: 1200px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } }\n" +
"        @media (max-width: 768px) { .stats-grid { grid-template-columns: 1fr; } }\n" +
"        .stat-card {\n" +
"            background: linear-gradient(135deg, rgba(99, 102, 241, 0.08), rgba(6, 182, 212, 0.04));\n" +
"            border: 1px solid var(--border-subtle);\n" +
"            border-radius: 12px; padding: 20px; text-align: center;\n" +
"            transition: all 0.2s ease;\n" +
"        }\n" +
"        .stat-card:hover { transform: translateY(-2px); border-color: rgba(99, 102, 241, 0.3); }\n" +
"        .stat-value { font-size: 32px; font-weight: 800; color: var(--text-bright); margin-bottom: 4px; }\n" +
"        .stat-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 1px; }\n" +
"        .btn {\n" +
"            padding: 10px 20px;\n" +
"            border: none;\n" +
"            border-radius: 10px;\n" +
"            cursor: pointer;\n" +
"            font-size: 13px;\n" +
"            font-weight: 600;\n" +
"            transition: all 0.2s ease;\n" +
"            display: inline-flex;\n" +
"            align-items: center;\n" +
"            gap: 8px;\n" +
"        }\n" +
"        .btn-primary { background: linear-gradient(135deg, var(--primary), var(--primary-dark)); color: white; }\n" +
"        .btn-primary:hover { transform: translateY(-1px); }\n" +
"        .btn-secondary { background: rgba(255, 255, 255, 0.05); color: var(--text-normal); border: 1px solid var(--border-light); }\n" +
"        .btn-secondary:hover { background: rgba(255, 255, 255, 0.1); }\n" +
"        .btn-danger { background: linear-gradient(135deg, var(--danger), #dc2626); color: white; }\n" +
"        .btn-sm { padding: 6px 12px; font-size: 12px; border-radius: 6px; }\n" +
"        .data-table { width: 100%; border-collapse: separate; border-spacing: 0; }\n" +
"        .data-table th, .data-table td { padding: 14px 16px; text-align: left; }\n" +
"        .data-table th {\n" +
"            font-size: 10px; font-weight: 700; color: var(--text-muted);\n" +
"            text-transform: uppercase; letter-spacing: 1px;\n" +
"            background: rgba(0, 0, 0, 0.3);\n" +
"            border-bottom: 1px solid var(--border-subtle);\n" +
"        }\n" +
"        .data-table td { border-bottom: 1px solid var(--border-subtle); }\n" +
"        .data-table tr:hover td { background: rgba(99, 102, 241, 0.05); }\n" +
"        .badge {\n" +
"            display: inline-flex; align-items: center;\n" +
"            padding: 4px 10px; border-radius: 6px;\n" +
"            font-size: 11px; font-weight: 600;\n" +
"        }\n" +
"        .badge-success { background: rgba(34, 197, 94, 0.2); color: var(--success); }\n" +
"        .badge-warning { background: rgba(234, 179, 8, 0.2); color: var(--warning); }\n" +
"        .badge-danger { background: rgba(239, 68, 68, 0.2); color: var(--danger); }\n" +
"        .badge-primary { background: rgba(99, 102, 241, 0.2); color: var(--primary-light); }\n" +
"        .log-viewer {\n" +
"            background: rgba(0, 0, 0, 0.4);\n" +
"            border: 1px solid var(--border-subtle);\n" +
"            border-radius: 10px;\n" +
"            padding: 16px;\n" +
"            font-family: 'JetBrains Mono', 'Fira Code', monospace;\n" +
"            font-size: 12px;\n" +
"            max-height: 400px;\n" +
"            overflow-y: auto;\n" +
"        }\n" +
"        .log-entry { padding: 4px 0; border-bottom: 1px solid var(--border-subtle); }\n" +
"        .log-entry:last-child { border-bottom: none; }\n" +
"        .log-time { color: var(--text-muted); }\n" +
"        .log-level-INFO { color: var(--success); }\n" +
"        .log-level-WARN { color: var(--warning); }\n" +
"        .log-level-ERROR { color: var(--danger); }\n" +
"        .log-level-DEBUG { color: var(--text-muted); }\n" +
"        .log-component { color: var(--accent); }\n" +
"        .log-message { color: var(--text-normal); }\n" +
"        .progress-bar {\n" +
"            height: 8px;\n" +
"            background: rgba(255, 255, 255, 0.1);\n" +
"            border-radius: 4px;\n" +
"            overflow: hidden;\n" +
"        }\n" +
"        .progress-fill {\n" +
"            height: 100%;\n" +
"            background: linear-gradient(90deg, var(--primary), var(--accent));\n" +
"            border-radius: 4px;\n" +
"            transition: width 0.3s ease;\n" +
"        }\n" +
"        .hidden { display: none !important; }\n" +
"        .page { animation: fadeIn 0.3s ease; }\n" +
"        @keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }\n" +
"        ::-webkit-scrollbar { width: 6px; height: 6px; }\n" +
"        ::-webkit-scrollbar-track { background: transparent; }\n" +
"        ::-webkit-scrollbar-thumb { background: var(--border-light); border-radius: 3px; }\n" +
"    </style>\n" +
"</head>\n" +
"<body>\n" +
"    <div class=\"app\">\n" +
"        <aside class=\"sidebar\">\n" +
"            <div class=\"sidebar-header\">\n" +
"                <div class=\"logo\">\n" +
"                    <div class=\"logo-icon\">DB</div>\n" +
"                    <div>\n" +
"                        <div class=\"logo-text\">TYPUI Manager</div>\n" +
"                        <div class=\"logo-sub\">v2.1.0</div>\n" +
"                    </div>\n" +
"                </div>\n" +
"            </div>\n" +
"            <nav class=\"sidebar-nav\">\n" +
"                <div class=\"nav-section\">\n" +
"                    <div class=\"nav-section-title\">Overview</div>\n" +
"                    <div class=\"nav-item active\" data-page=\"dashboard\" onclick=\"showPage('dashboard')\">\n" +
"                        <span class=\"nav-icon\">📊</span><span>Dashboard</span>\n" +
"                    </div>\n" +
"                    <div class=\"nav-item\" data-page=\"logs\" onclick=\"showPage('logs')\">\n" +
"                        <span class=\"nav-icon\">📋</span><span>Logs</span>\n" +
"                    </div>\n" +
"                </div>\n" +
"                <div class=\"nav-section\">\n" +
"                    <div class=\"nav-section-title\">Data</div>\n" +
"                    <div class=\"nav-item\" data-page=\"databases\" onclick=\"showPage('databases')\">\n" +
"                        <span class=\"nav-icon\">🗄️</span><span>Databases</span>\n" +
"                    </div>\n" +
"                    <div class=\"nav-item\" data-page=\"storage\" onclick=\"showPage('storage')\">\n" +
"                        <span class=\"nav-icon\">📁</span><span>Storage</span>\n" +
"                    </div>\n" +
"                </div>\n" +
"                <div class=\"nav-section\">\n" +
"                    <div class=\"nav-section-title\">System</div>\n" +
"                    <div class=\"nav-item\" data-page=\"monitor\" onclick=\"showPage('monitor')\">\n" +
"                        <span class=\"nav-icon\">📈</span><span>Monitor</span>\n" +
"                    </div>\n" +
"                    <div class=\"nav-item\" data-page=\"config\" onclick=\"showPage('config')\">\n" +
"                        <span class=\"nav-icon\">⚙️</span><span>Config</span>\n" +
"                    </div>\n" +
"                </div>\n" +
"            </nav>\n" +
"            <div class=\"sidebar-footer\">\n" +
"                <div class=\"db-info\">\n" +
"                    <div class=\"db-label\">Database</div>\n" +
"                    <div class=\"db-name\" id=\"currentDbName\">-</div>\n" +
"                </div>\n" +
"            </div>\n" +
"        </aside>\n" +
"        <main class=\"main\">\n" +
"            <div id=\"dashboardPage\" class=\"page\">\n" +
"                <div class=\"page-header\">\n" +
"                    <h1 class=\"page-title\">Dashboard</h1>\n" +
"                    <button class=\"btn btn-secondary\" onclick=\"refreshDashboard()\">🔄 Refresh</button>\n" +
"                </div>\n" +
"                <div class=\"stats-grid\" id=\"statsGrid\"></div>\n" +
"                <div class=\"card\">\n" +
"                    <div class=\"card-header\">\n" +
"                        <h3 class=\"card-title\">Services Status</h3>\n" +
"                    </div>\n" +
"                    <div id=\"servicesStatus\"></div>\n" +
"                </div>\n" +
"            </div>\n" +
"            <div id=\"logsPage\" class=\"page hidden\">\n" +
"                <div class=\"page-header\">\n" +
"                    <h1 class=\"page-title\">System Logs</h1>\n" +
"                    <div>\n" +
"                        <button class=\"btn btn-secondary btn-sm\" onclick=\"refreshLogs()\">🔄 Refresh</button>\n" +
"                        <button class=\"btn btn-danger btn-sm\" onclick=\"clearLogs()\">🗑️ Clear</button>\n" +
"                    </div>\n" +
"                </div>\n" +
"                <div class=\"card\">\n" +
"                    <div class=\"card-header\">\n" +
"                        <h3 class=\"card-title\">Recent Logs</h3>\n" +
"                        <select id=\"logLevelFilter\" onchange=\"refreshLogs()\" style=\"padding:6px 12px;border-radius:6px;background:rgba(0,0,0,0.3);border:1px solid var(--border-subtle);color:var(--text-normal);\">\n" +
"                            <option value=\"\">All Levels</option>\n" +
"                            <option value=\"ERROR\">Error</option>\n" +
"                            <option value=\"WARN\">Warning</option>\n" +
"                            <option value=\"INFO\">Info</option>\n" +
"                            <option value=\"DEBUG\">Debug</option>\n" +
"                        </select>\n" +
"                    </div>\n" +
"                    <div class=\"log-viewer\" id=\"logViewer\"></div>\n" +
"                </div>\n" +
"            </div>\n" +
"            <div id=\"databasesPage\" class=\"page hidden\">\n" +
"                <div class=\"page-header\">\n" +
"                    <h1 class=\"page-title\">Databases</h1>\n" +
"                    <button class=\"btn btn-secondary\" onclick=\"refreshDatabases()\">🔄 Refresh</button>\n" +
"                </div>\n" +
"                <div class=\"card\">\n" +
"                    <div class=\"card-header\"><h3 class=\"card-title\">Database List</h3></div>\n" +
"                    <div id=\"databasesList\"></div>\n" +
"                </div>\n" +
"            </div>\n" +
"            <div id=\"storagePage\" class=\"page hidden\">\n" +
"                <div class=\"page-header\">\n" +
"                    <h1 class=\"page-title\">File Storage</h1>\n" +
"                    <button class=\"btn btn-secondary\" onclick=\"refreshStorage()\">🔄 Refresh</button>\n" +
"                </div>\n" +
"                <div class=\"card\">\n" +
"                    <div class=\"card-header\"><h3 class=\"card-title\">Storage Buckets</h3></div>\n" +
"                    <div id=\"storageList\"></div>\n" +
"                </div>\n" +
"            </div>\n" +
"            <div id=\"monitorPage\" class=\"page hidden\">\n" +
"                <div class=\"page-header\">\n" +
"                    <h1 class=\"page-title\">System Monitor</h1>\n" +
"                    <button class=\"btn btn-secondary\" onclick=\"refreshMonitor()\">🔄 Refresh</button>\n" +
"                </div>\n" +
"                <div class=\"stats-grid\">\n" +
"                    <div class=\"stat-card\">\n" +
"                        <div class=\"stat-value\" id=\"memUsed\">-</div>\n" +
"                        <div class=\"stat-label\">Memory Used</div>\n" +
"                    </div>\n" +
"                    <div class=\"stat-card\">\n" +
"                        <div class=\"stat-value\" id=\"memMax\">-</div>\n" +
"                        <div class=\"stat-label\">Max Memory</div>\n" +
"                    </div>\n" +
"                    <div class=\"stat-card\">\n" +
"                        <div class=\"stat-value\" id=\"threadCount\">-</div>\n" +
"                        <div class=\"stat-label\">Threads</div>\n" +
"                    </div>\n" +
"                    <div class=\"stat-card\">\n" +
"                        <div class=\"stat-value\" id=\"uptime\">-</div>\n" +
"                        <div class=\"stat-label\">Uptime</div>\n" +
"                    </div>\n" +
"                </div>\n" +
"                <div class=\"card\">\n" +
"                    <div class=\"card-header\"><h3 class=\"card-title\">Memory Usage</h3></div>\n" +
"                    <div class=\"progress-bar\"><div class=\"progress-fill\" id=\"memProgress\" style=\"width:0%\"></div></div>\n" +
"                    <p style=\"margin-top:12px;color:var(--text-muted);font-size:12px;\" id=\"memPercent\">0%</p>\n" +
"                </div>\n" +
"                <div class=\"card\">\n" +
"                    <div class=\"card-header\"><h3 class=\"card-title\">Log Statistics</h3></div>\n" +
"                    <div id=\"logStats\"></div>\n" +
"                </div>\n" +
"            </div>\n" +
"            <div id=\"configPage\" class=\"page hidden\">\n" +
"                <div class=\"page-header\">\n" +
"                    <h1 class=\"page-title\">Configuration</h1>\n" +
"                    <button class=\"btn btn-primary\" onclick=\"saveConfig()\">💾 Save</button>\n" +
"                </div>\n" +
"                <div class=\"card\">\n" +
"                    <div class=\"card-header\"><h3 class=\"card-title\">Settings</h3></div>\n" +
"                    <div id=\"configForm\"></div>\n" +
"                </div>\n" +
"            </div>\n" +
"        </main>\n" +
"    </div>\n" +
"    <script>\n" +
"        const API = window.location.origin;\n" +
"        let currentConfig = {};\n" +
"        async function fetchApi(endpoint, options = {}) {\n" +
"            try {\n" +
"                const res = await fetch(API + endpoint, { ...options, headers: { 'Content-Type': 'application/json', ...options.headers } });\n" +
"                return await res.json();\n" +
"            } catch (e) { console.error('API Error:', e); return null; }\n" +
"        }\n" +
"        function showPage(p) {\n" +
"            document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));\n" +
"            document.querySelector('.nav-item[data-page=\"' + p + '\"]').classList.add('active');\n" +
"            document.querySelectorAll('.page').forEach(el => el.classList.add('hidden'));\n" +
"            document.getElementById(p + 'Page').classList.remove('hidden');\n" +
"            if (p === 'dashboard') refreshDashboard();\n" +
"            if (p === 'logs') refreshLogs();\n" +
"            if (p === 'databases') refreshDatabases();\n" +
"            if (p === 'storage') refreshStorage();\n" +
"            if (p === 'monitor') refreshMonitor();\n" +
"            if (p === 'config') loadConfig();\n" +
"        }\n" +
"        async function refreshDashboard() {\n" +
"            const s = await fetchApi('/api/status');\n" +
"            if (!s) return;\n" +
"            document.getElementById('currentDbName').textContent = s.databaseName || '-';\n" +
"            document.getElementById('statsGrid').innerHTML = `\n" +
"                <div class=\"stat-card\"><div class=\"stat-value\">${s.databases || 0}</div><div class=\"stat-label\">Databases</div></div>\n" +
"                <div class=\"stat-card\"><div class=\"stat-value\">${s.collections || 0}</div><div class=\"stat-label\">Collections</div></div>\n" +
"                <div class=\"stat-card\"><div class=\"stat-value\">${s.documents || 0}</div><div class=\"stat-label\">Documents</div></div>\n" +
"                <div class=\"stat-card\"><div class=\"stat-value\">${s.dataSize || '0 B'}</div><div class=\"stat-label\">Data Size</div></div>\n" +
"            `;\n" +
"            const services = [\n" +
"                { name: 'Document Server', port: s.documentPort, running: s.documentServer },\n" +
"                { name: 'Storage Server', port: s.storagePort, running: s.storageServer },\n" +
"                { name: 'Admin Server', port: s.adminPort, running: true }\n" +
"            ];\n" +
"            document.getElementById('servicesStatus').innerHTML = services.map(svc => `\n" +
"                <div style=\"display:flex;justify-content:space-between;align-items:center;padding:12px 0;border-bottom:1px solid var(--border-subtle);\">\n" +
"                    <div><strong>${svc.name}</strong><br><span style=\"color:var(--text-muted);font-size:12px;\">Port: ${svc.port}</span></div>\n" +
"                    <span class=\"badge ${svc.running ? 'badge-success' : 'badge-danger'}\">${svc.running ? 'Running' : 'Stopped'}</span>\n" +
"                </div>\n" +
"            `).join('');\n" +
"        }\n" +
"        async function refreshLogs() {\n" +
"            const level = document.getElementById('logLevelFilter').value;\n" +
"            const data = await fetchApi('/api/logs?limit=100' + (level ? '&level=' + level : ''));\n" +
"            if (!data || !data.logs) return;\n" +
"            document.getElementById('logViewer').innerHTML = data.logs.map(log => {\n" +
"                const time = new Date(log.timestamp).toLocaleTimeString();\n" +
"                return `<div class=\"log-entry\">\n" +
"                    <span class=\"log-time\">[${time}]</span>\n" +
"                    <span class=\"log-level-${log.level}\">[${log.level}]</span>\n" +
"                    <span class=\"log-component\">[${log.component}]</span>\n" +
"                    <span class=\"log-message\">${log.message}</span>\n" +
"                </div>`;\n" +
"            }).join('') || '<div style=\"color:var(--text-muted);text-align:center;padding:20px;\">No logs</div>';\n" +
"        }\n" +
"        async function clearLogs() {\n" +
"            if (!confirm('Clear all logs?')) return;\n" +
"            await fetchApi('/api/logs/clear', { method: 'POST' });\n" +
"            refreshLogs();\n" +
"        }\n" +
"        async function refreshDatabases() {\n" +
"            const data = await fetchApi('/api/databases');\n" +
"            if (!data || !data.databases) return;\n" +
"            document.getElementById('databasesList').innerHTML = data.databases.length ? \n" +
"                `<table class=\"data-table\"><thead><tr><th>Name</th><th>Collections</th><th>Size</th><th>Actions</th></tr></thead><tbody>\n" +
"                ${data.databases.map(db => `<tr><td><strong>${db.name}</strong></td><td>${db.collections}</td><td>${db.size}</td><td><button class=\"btn btn-secondary btn-sm\" onclick=\"viewDb('${db.name}')\">View</button></td></tr>`).join('')}\n" +
"                </tbody></table>` : '<div style=\"color:var(--text-muted);text-align:center;padding:40px;\">No databases</div>';\n" +
"        }\n" +
"        async function viewDb(name) {\n" +
"            const data = await fetchApi('/api/databases/' + name + '/collections');\n" +
"            alert('Database: ' + name + '\\nCollections: ' + (data.collections ? data.collections.length : 0));\n" +
"        }\n" +
"        async function refreshStorage() {\n" +
"            document.getElementById('storageList').innerHTML = '<div style=\"color:var(--text-muted);text-align:center;padding:40px;\">Storage buckets will appear here</div>';\n" +
"        }\n" +
"        async function refreshMonitor() {\n" +
"            const data = await fetchApi('/api/system/stats');\n" +
"            if (!data || !data.stats) return;\n" +
"            const stats = data.stats;\n" +
"            document.getElementById('memUsed').textContent = formatSize(stats.memory.used);\n" +
"            document.getElementById('memMax').textContent = formatSize(stats.memory.max);\n" +
"            document.getElementById('threadCount').textContent = stats.threads.active;\n" +
"            document.getElementById('uptime').textContent = stats.uptimeFormatted || '-';\n" +
"            const percent = stats.memory.usagePercent.toFixed(1);\n" +
"            document.getElementById('memProgress').style.width = percent + '%';\n" +
"            document.getElementById('memPercent').textContent = percent + '% memory used';\n" +
"            if (stats.logs) {\n" +
"                document.getElementById('logStats').innerHTML = `\n" +
"                    <div style=\"display:grid;grid-template-columns:repeat(3,1fr);gap:12px;\">\n" +
"                        <div style=\"text-align:center;padding:12px;background:rgba(0,0,0,0.2);border-radius:8px;\">\n" +
"                            <div style=\"font-size:20px;font-weight:700;color:var(--text-bright);\">${stats.logs.archiveCount || 0}</div>\n" +
"                            <div style=\"font-size:11px;color:var(--text-muted);\">Log Archives</div>\n" +
"                        </div>\n" +
"                        <div style=\"text-align:center;padding:12px;background:rgba(0,0,0,0.2);border-radius:8px;\">\n" +
"                            <div style=\"font-size:20px;font-weight:700;color:var(--text-bright);\">${stats.logs.archiveSizeFormatted || '0 B'}</div>\n" +
"                            <div style=\"font-size:11px;color:var(--text-muted);\">Archive Size</div>\n" +
"                        </div>\n" +
"                        <div style=\"text-align:center;padding:12px;background:rgba(0,0,0,0.2);border-radius:8px;\">\n" +
"                            <div style=\"font-size:20px;font-weight:700;color:var(--text-bright);\">${stats.logs.currentLogSizeFormatted || '0 B'}</div>\n" +
"                            <div style=\"font-size:11px;color:var(--text-muted);\">Current Log</div>\n" +
"                        </div>\n" +
"                    </div>\n" +
"                `;\n" +
"            }\n" +
"        }\n" +
"        async function loadConfig() {\n" +
"            const data = await fetchApi('/api/config');\n" +
"            if (!data) return;\n" +
"            currentConfig = data.config || {};\n" +
"            document.getElementById('configForm').innerHTML = `\n" +
"                <div style=\"margin-bottom:16px;\">\n" +
"                    <label style=\"display:block;font-size:12px;color:var(--text-muted);margin-bottom:6px;\">Database Name</label>\n" +
"                    <input type=\"text\" id=\"cfgDb\" value=\"${currentConfig.databaseName || ''}\" style=\"width:100%;padding:10px;border-radius:8px;background:rgba(0,0,0,0.3);border:1px solid var(--border-subtle);color:var(--text-normal);\">\n" +
"                </div>\n" +
"                <div style=\"margin-bottom:16px;\">\n" +
"                    <label style=\"display:block;font-size:12px;color:var(--text-muted);margin-bottom:6px;\">Document Port</label>\n" +
"                    <input type=\"number\" id=\"cfgDocPort\" value=\"${currentConfig.documentDatabase?.port || 27018}\" style=\"width:100%;padding:10px;border-radius:8px;background:rgba(0,0,0,0.3);border:1px solid var(--border-subtle);color:var(--text-normal);\">\n" +
"                </div>\n" +
"                <div style=\"margin-bottom:16px;\">\n" +
"                    <label style=\"display:block;font-size:12px;color:var(--text-muted);margin-bottom:6px;\">Storage Port</label>\n" +
"                    <input type=\"number\" id=\"cfgFilePort\" value=\"${currentConfig.fileStorage?.port || 9001}\" style=\"width:100%;padding:10px;border-radius:8px;background:rgba(0,0,0,0.3);border:1px solid var(--border-subtle);color:var(--text-normal);\">\n" +
"                </div>\n" +
"            `;\n" +
"        }\n" +
"        async function saveConfig() {\n" +
"            const cfg = {\n" +
"                databaseName: document.getElementById('cfgDb').value,\n" +
"                documentDatabase: { port: parseInt(document.getElementById('cfgDocPort').value) },\n" +
"                fileStorage: { port: parseInt(document.getElementById('cfgFilePort').value) }\n" +
"            };\n" +
"            const res = await fetchApi('/api/config', { method: 'POST', body: JSON.stringify(cfg) });\n" +
"            if (res && res.success) alert('Config saved. Restart required for some changes.');\n" +
"        }\n" +
"        function formatSize(bytes) {\n" +
"            if (!bytes) return '0 B';\n" +
"            if (bytes < 1024) return bytes + ' B';\n" +
"            if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';\n" +
"            if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';\n" +
"            return (bytes / 1024 / 1024 / 1024).toFixed(1) + ' GB';\n" +
"        }\n" +
"        refreshDashboard();\n" +
"    </script>\n" +
"</body>\n" +
"</html>";
    }
}
