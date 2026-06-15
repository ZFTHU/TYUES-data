package com.typui.database.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typui.database.ConfigManager;
import com.typui.database.auth.AuthService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件存储服务器
 * 端口9000，HTTP协议
 * 类似MinIO的文件存储服务
 * 
 * 目录结构: tec/{databaseName}/{bucket}/{category}/{extension}/
 * 每个项目的文件完全隔离
 */
public class StorageServer {
    
    private static final Logger logger = LoggerFactory.getLogger(StorageServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ConfigManager configManager;
    private final File dataDir;
    private final String databaseName;
    private final Map<String, StorageBucket> buckets;
    private final AuthService authService;
    private Javalin app;
    private boolean running;
    
    public StorageServer(ConfigManager configManager, AuthService authService) {
        this.configManager = configManager;
        this.authService = authService;
        this.databaseName = configManager.getDatabaseName();
        this.dataDir = new File(configManager.getFileStorageDataDir());
        this.buckets = new ConcurrentHashMap<>();
        this.running = false;
        
        initializeDataDir();
    }
    
    /**
     * 初始化数据目录
     */
    private void initializeDataDir() {
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            logger.info("文件存储目录创建成功: {}", dataDir.getAbsolutePath());
        }
        
        loadBuckets();
        
        if (!buckets.containsKey(databaseName)) {
            buckets.put(databaseName, new StorageBucket(databaseName, dataDir));
            logger.info("默认存储桶创建成功: {}", databaseName);
        }
    }
    
    /**
     * 加载所有存储桶
     */
    private void loadBuckets() {
        File[] dirs = dataDir.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                String bucketName = dir.getName();
                if (!bucketName.startsWith(".")) {
                    buckets.put(bucketName, new StorageBucket(bucketName, dataDir));
                    logger.info("存储桶加载成功: {}", bucketName);
                }
            }
        }
    }
    
    /**
     * 启动服务器
     */
    public void start() {
        if (running) {
            logger.warn("文件存储服务器已在运行中");
            return;
        }
        
        int port = configManager.getFileStoragePort();
        
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.maxRequestSize = configManager.getMaxFileSize();
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
        logger.info("文件存储服务器启动成功！");
        logger.info("端口: {}", port);
        logger.info("数据库名: {}", databaseName);
        logger.info("数据目录: {}", dataDir.getAbsolutePath());
        logger.info("默认存储桶: {}", databaseName);
        logger.info("========================================");
    }
    
    /**
     * 停止服务器
     */
    public void stop() {
        if (app != null) {
            app.stop();
            running = false;
            logger.info("文件存储服务器已停止");
        }
    }
    
    /**
     * 设置路由
     */
    private void setupRoutes() {
        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        });
        
        app.options("/*", ctx -> ctx.status(200).result());
        
        app.get("/", this::handleRoot);
        app.get("/status", this::handleStatus);
        app.get("/ui", this::handleUI);
        
        app.get("/buckets", this::handleListBuckets);
        app.post("/buckets", this::handleCreateBucket);
        app.delete("/buckets/{bucket}", this::handleDeleteBucket);
        app.get("/buckets/{bucket}", this::handleBucketInfo);
        
        app.post("/buckets/{bucket}/upload", this::handleUpload);
        app.post("/upload", this::handleUploadDefault);
        app.get("/buckets/{bucket}/download/{objectId}", this::handleDownload);
        app.get("/download/{objectId}", this::handleDownloadDefault);
        app.get("/buckets/{bucket}/preview/{objectId}", this::handlePreview);
        app.get("/preview/{objectId}", this::handlePreviewDefault);
        app.delete("/buckets/{bucket}/{objectId}", this::handleDelete);
        app.delete("/{objectId}", this::handleDeleteDefault);
        app.get("/buckets/{bucket}/files", this::handleListFiles);
        app.get("/files", this::handleListFilesDefault);
        app.get("/buckets/{bucket}/files/{category}", this::handleListFilesByCategory);
        app.get("/buckets/{bucket}/search", this::handleSearch);
        app.get("/buckets/{bucket}/metadata/{objectId}", this::handleGetMetadata);
        
        app.get("/categories", this::handleListCategories);
        app.get("/types", this::handleListFileTypes);
    }
    
    /**
     * 根路径处理
     */
    private void handleRoot(Context ctx) {
        ctx.json(Map.of(
            "name", "TYPUI File Storage",
            "version", "2.0.0",
            "type", "storage",
            "port", configManager.getFileStoragePort(),
            "databaseName", databaseName,
            "defaultBucket", databaseName,
            "buckets", buckets.size()
        ));
    }
    
    /**
     * 状态处理
     */
    private void handleStatus(Context ctx) {
        long totalSize = 0;
        int totalFiles = 0;
        
        for (StorageBucket bucket : buckets.values()) {
            totalSize += bucket.getTotalSize();
            totalFiles += bucket.getFileCount();
        }
        
        ctx.json(Map.of(
            "success", true,
            "running", running,
            "databaseName", databaseName,
            "dataDir", dataDir.getAbsolutePath(),
            "buckets", buckets.size(),
            "totalFiles", totalFiles,
            "totalSize", totalSize
        ));
    }
    
    /**
     * UI界面处理
     */
    private void handleUI(Context ctx) {
        ctx.html(generateUI());
    }
    
    /**
     * 列出存储桶
     */
    private void handleListBuckets(Context ctx) {
        List<Map<String, Object>> bucketList = new ArrayList<>();
        for (Map.Entry<String, StorageBucket> entry : buckets.entrySet()) {
            StorageBucket bucket = entry.getValue();
            bucketList.add(Map.of(
                "name", entry.getKey(),
                "fileCount", bucket.getFileCount(),
                "totalSize", bucket.getTotalSize(),
                "publicRead", bucket.isPublicRead(),
                "isDefault", entry.getKey().equals(databaseName)
            ));
        }
        ctx.json(Map.of("success", true, "databaseName", databaseName, "buckets", bucketList));
    }
    
    /**
     * 创建存储桶
     */
    private void handleCreateBucket(Context ctx) {
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String bucketName = (String) body.get("name");
            
            if (bucketName == null || bucketName.isEmpty()) {
                ctx.status(400).json(Map.of("success", false, "error", "存储桶名称不能为空"));
                return;
            }
            
            if (buckets.containsKey(bucketName)) {
                ctx.status(400).json(Map.of("success", false, "error", "存储桶已存在"));
                return;
            }
            
            buckets.put(bucketName, new StorageBucket(bucketName, dataDir));
            ctx.json(Map.of("success", true, "message", "存储桶创建成功", "name", bucketName));
            
        } catch (Exception e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    /**
     * 删除存储桶
     */
    private void handleDeleteBucket(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        
        if (bucketName.equals(databaseName)) {
            ctx.status(400).json(Map.of("success", false, "error", "不能删除默认存储桶"));
            return;
        }
        
        StorageBucket removed = buckets.remove(bucketName);
        if (removed != null) {
            removed.clear();
            File bucketDir = new File(dataDir, bucketName);
            deleteDirectory(bucketDir);
            ctx.json(Map.of("success", true, "message", "存储桶删除成功"));
        } else {
            ctx.status(404).json(Map.of("success", false, "error", "存储桶不存在"));
        }
    }
    
    /**
     * 存储桶信息
     */
    private void handleBucketInfo(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        StorageBucket bucket = buckets.get(bucketName);
        
        if (bucket == null) {
            ctx.status(404).json(Map.of("success", false, "error", "存储桶不存在"));
            return;
        }
        
        ctx.json(Map.of(
            "success", true,
            "bucket", Map.of(
                "name", bucket.getName(),
                "fileCount", bucket.getFileCount(),
                "totalSize", bucket.getTotalSize(),
                "publicRead", bucket.isPublicRead(),
                "isDefault", bucketName.equals(databaseName),
                "structure", bucket.getDirectoryStructure()
            )
        ));
    }
    
    /**
     * 上传文件（指定存储桶）
     */
    private void handleUpload(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        uploadToBucket(ctx, bucketName);
    }
    
    /**
     * 上传文件（默认存储桶）
     */
    private void handleUploadDefault(Context ctx) {
        uploadToBucket(ctx, databaseName);
    }
    
    /**
     * 上传文件到指定存储桶
     */
    private void uploadToBucket(Context ctx, String bucketName) {
        StorageBucket bucket = buckets.computeIfAbsent(bucketName, 
            name -> new StorageBucket(name, dataDir));
        
        try {
            UploadedFile uploadedFile = ctx.uploadedFile("file");
            
            if (uploadedFile == null) {
                ctx.status(400).json(Map.of("success", false, "error", "未找到上传文件"));
                return;
            }
            
            String filename = uploadedFile.filename();
            String contentType = uploadedFile.contentType();
            
            byte[] data;
            try (InputStream is = uploadedFile.content()) {
                data = is.readAllBytes();
            }
            
            long maxSize = configManager.getMaxFileSize();
            if (data.length > maxSize) {
                ctx.status(400).json(Map.of("success", false, "error", 
                    "文件大小超出限制，最大允许: " + (maxSize / 1024 / 1024) + "MB"));
                return;
            }
            
            String uploader = ctx.header("X-Uploader");
            if (uploader == null) {
                uploader = "anonymous";
            }
            
            FileMetadata metadata = bucket.upload(filename, data, contentType, uploader);
            
            ctx.status(201).json(Map.of(
                "success", true,
                "message", "文件上传成功",
                "databaseName", databaseName,
                "bucket", bucketName,
                "objectId", metadata.getObjectId(),
                "objectName", metadata.getObjectName(),
                "size", metadata.getSize(),
                "category", metadata.getCategory(),
                "categoryDir", metadata.getCategoryDir(),
                "contentType", metadata.getContentType()
            ));
            
        } catch (Exception e) {
            logger.error("文件上传失败", e);
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
    
    /**
     * 下载文件（指定存储桶）
     */
    private void handleDownload(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        downloadFromBucket(ctx, bucketName);
    }
    
    /**
     * 下载文件（默认存储桶）
     */
    private void handleDownloadDefault(Context ctx) {
        downloadFromBucket(ctx, databaseName);
    }
    
    /**
     * 从指定存储桶下载文件
     */
    private void downloadFromBucket(Context ctx, String bucketName) {
        String objectIdOrName = ctx.pathParam("objectId");
        
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404).json(Map.of("success", false, "error", "存储桶不存在"));
            return;
        }
        
        FileMetadata metadata = bucket.getMetadata(objectIdOrName);
        byte[] data = null;
        
        if (metadata != null) {
            data = bucket.download(objectIdOrName);
        } else {
            data = bucket.downloadByName(objectIdOrName);
            if (data != null) {
                metadata = bucket.getMetadataByName(objectIdOrName);
            }
        }
        
        if (data == null || metadata == null) {
            ctx.status(404).json(Map.of("success", false, "error", "文件不存在"));
            return;
        }
        
        ctx.header("Content-Disposition", "attachment; filename=\"" + metadata.getObjectName() + "\"");
        ctx.header("Content-Type", metadata.getContentType());
        ctx.result(data);
    }
    
    /**
     * 预览文件（指定存储桶）
     */
    private void handlePreview(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        previewFromBucket(ctx, bucketName);
    }
    
    /**
     * 预览文件（默认存储桶）
     */
    private void handlePreviewDefault(Context ctx) {
        previewFromBucket(ctx, databaseName);
    }
    
    /**
     * 从指定存储桶预览文件
     */
    private void previewFromBucket(Context ctx, String bucketName) {
        String objectIdOrName = ctx.pathParam("objectId");
        
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404).json(Map.of("success", false, "error", "存储桶不存在"));
            return;
        }
        
        FileMetadata metadata = bucket.getMetadata(objectIdOrName);
        byte[] data = null;
        
        if (metadata != null) {
            data = bucket.download(objectIdOrName);
        } else {
            data = bucket.downloadByName(objectIdOrName);
            if (data != null) {
                metadata = bucket.getMetadataByName(objectIdOrName);
            }
        }
        
        if (data == null || metadata == null) {
            ctx.status(404).json(Map.of("success", false, "error", "文件不存在"));
            return;
        }
        
        ctx.header("Content-Type", metadata.getContentType());
        ctx.header("Content-Disposition", "inline; filename=\"" + metadata.getObjectName() + "\"");
        ctx.result(data);
    }
    
    /**
     * 删除文件（指定存储桶）
     */
    private void handleDelete(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        String objectId = ctx.pathParam("objectId");
        deleteFromBucket(ctx, bucketName, objectId);
    }
    
    /**
     * 删除文件（默认存储桶）
     */
    private void handleDeleteDefault(Context ctx) {
        String objectId = ctx.pathParam("objectId");
        deleteFromBucket(ctx, databaseName, objectId);
    }
    
    /**
     * 从指定存储桶删除文件
     */
    private void deleteFromBucket(Context ctx, String bucketName, String objectId) {
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404).json(Map.of("success", false, "error", "存储桶不存在"));
            return;
        }
        
        if (bucket.delete(objectId)) {
            ctx.json(Map.of("success", true, "message", "文件删除成功"));
        } else {
            ctx.status(404).json(Map.of("success", false, "error", "文件不存在"));
        }
    }
    
    /**
     * 列出文件（指定存储桶）
     */
    private void handleListFiles(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        listFilesFromBucket(ctx, bucketName);
    }
    
    /**
     * 列出文件（默认存储桶）
     */
    private void handleListFilesDefault(Context ctx) {
        listFilesFromBucket(ctx, databaseName);
    }
    
    /**
     * 列出指定存储桶的文件
     */
    private void listFilesFromBucket(Context ctx, String bucketName) {
        StorageBucket bucket = buckets.get(bucketName);
        
        if (bucket == null) {
            ctx.status(404).json(Map.of("success", false, "error", "存储桶不存在"));
            return;
        }
        
        List<FileMetadata> files = bucket.listAll();
        List<Map<String, Object>> fileList = new ArrayList<>();
        
        for (FileMetadata meta : files) {
            fileList.add(meta.toMap());
        }
        
        ctx.json(Map.of("success", true, "databaseName", databaseName, "bucket", bucketName, "files", fileList, "count", fileList.size()));
    }
    
    /**
     * 按分类列出文件
     */
    private void handleListFilesByCategory(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        String categoryName = ctx.pathParam("category");
        
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404).json(Map.of("success", false, "error", "存储桶不存在"));
            return;
        }
        
        List<FileMetadata> files = bucket.listByCategory(categoryName);
        List<Map<String, Object>> fileList = new ArrayList<>();
        
        for (FileMetadata meta : files) {
            fileList.add(meta.toMap());
        }
        
        ctx.json(Map.of("success", true, "files", fileList, "count", fileList.size(), "category", categoryName));
    }
    
    /**
     * 搜索文件
     */
    private void handleSearch(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        String keyword = ctx.queryParam("q");
        
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404).json(Map.of("success", false, "error", "存储桶不存在"));
            return;
        }
        
        if (keyword == null || keyword.isEmpty()) {
            ctx.json(Map.of("success", true, "files", Collections.emptyList(), "count", 0));
            return;
        }
        
        List<FileMetadata> files = bucket.search(keyword);
        List<Map<String, Object>> fileList = new ArrayList<>();
        
        for (FileMetadata meta : files) {
            fileList.add(meta.toMap());
        }
        
        ctx.json(Map.of("success", true, "files", fileList, "count", fileList.size(), "keyword", keyword));
    }
    
    /**
     * 获取文件元数据
     */
    private void handleGetMetadata(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        String objectIdOrName = ctx.pathParam("objectId");
        
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404).json(Map.of("success", false, "error", "存储桶不存在"));
            return;
        }
        
        FileMetadata metadata = bucket.getMetadata(objectIdOrName);
        
        if (metadata == null) {
            metadata = bucket.getMetadataByName(objectIdOrName);
        }
        
        if (metadata == null) {
            ctx.status(404).json(Map.of("success", false, "error", "文件不存在"));
            return;
        }
        
        ctx.json(Map.of("success", true, "metadata", metadata.toMap()));
    }
    
    /**
     * 列出文件分类
     */
    private void handleListCategories(Context ctx) {
        List<Map<String, Object>> categories = new ArrayList<>();
        
        for (FileTypeClassifier.FileCategory cat : FileTypeClassifier.FileCategory.values()) {
            categories.add(Map.of(
                "name", cat.name(),
                "directory", cat.getDirectory(),
                "displayName", cat.getDisplayName()
            ));
        }
        
        ctx.json(Map.of("success", true, "categories", categories));
    }
    
    /**
     * 列出支持的文件类型
     */
    private void handleListFileTypes(Context ctx) {
        ctx.json(Map.of(
            "success", true,
            "types", Map.of(
                "images", FileTypeClassifier.getImageExtensions(),
                "videos", FileTypeClassifier.getVideoExtensions(),
                "audio", FileTypeClassifier.getAudioExtensions(),
                "documents", FileTypeClassifier.getDocumentExtensions(),
                "archives", FileTypeClassifier.getArchiveExtensions(),
                "applications", FileTypeClassifier.getApplicationExtensions()
            )
        ));
    }
    
    /**
     * 删除目录
     */
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
    
    /**
     * 生成UI界面
     */
    private String generateUI() {
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>TYPUI 文件存储管理</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f5f5f5; }
                    .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px; text-align: center; }
                    .header .db-name { font-size: 12px; opacity: 0.8; margin-top: 5px; }
                    .container { max-width: 1200px; margin: 20px auto; padding: 0 20px; }
                    .card { background: white; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); padding: 20px; margin-bottom: 20px; }
                    .card h2 { margin-bottom: 15px; color: #333; }
                    .btn { background: #667eea; color: white; border: none; padding: 10px 20px; border-radius: 5px; cursor: pointer; margin: 5px; }
                    .btn:hover { background: #5a6fd6; }
                    .btn-danger { background: #e74c3c; }
                    .btn-danger:hover { background: #c0392b; }
                    .upload-area { border: 2px dashed #ddd; border-radius: 8px; padding: 40px; text-align: center; margin: 20px 0; }
                    .upload-area:hover { border-color: #667eea; }
                    input[type="file"] { display: none; }
                    .file-list { list-style: none; }
                    .file-item { display: flex; align-items: center; padding: 10px; border-bottom: 1px solid #eee; }
                    .file-item:hover { background: #f9f9f9; }
                    .file-icon { width: 40px; height: 40px; background: #667eea; border-radius: 8px; display: flex; align-items: center; justify-content: center; color: white; margin-right: 15px; }
                    .file-info { flex: 1; }
                    .file-name { font-weight: 500; color: #333; }
                    .file-meta { font-size: 12px; color: #999; }
                    .bucket-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 15px; }
                    .bucket-item { background: #f8f9fa; border-radius: 8px; padding: 15px; cursor: pointer; transition: all 0.3s; }
                    .bucket-item:hover { background: #e9ecef; transform: translateY(-2px); }
                    .bucket-item.default { border: 2px solid #667eea; }
                    .bucket-name { font-weight: 600; color: #333; margin-bottom: 5px; }
                    .bucket-stats { font-size: 12px; color: #666; }
                    .category-tabs { display: flex; gap: 10px; margin-bottom: 20px; flex-wrap: wrap; }
                    .category-tab { padding: 8px 16px; background: #f0f0f0; border-radius: 20px; cursor: pointer; font-size: 14px; }
                    .category-tab.active { background: #667eea; color: white; }
                    .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 15px; margin-bottom: 20px; }
                    .stat-card { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px; border-radius: 8px; text-align: center; }
                    .stat-value { font-size: 24px; font-weight: bold; }
                    .stat-label { font-size: 12px; opacity: 0.8; }
                    .modal { display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.5); z-index: 1000; }
                    .modal-content { background: white; margin: 100px auto; padding: 30px; border-radius: 8px; max-width: 400px; }
                    .modal input { width: 100%; padding: 10px; margin: 10px 0; border: 1px solid #ddd; border-radius: 5px; }
                    .hidden { display: none; }
                    .default-badge { background: #10b981; color: white; font-size: 10px; padding: 2px 6px; border-radius: 10px; margin-left: 8px; }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>TYPUI 文件存储管理</h1>
                    <div class="db-name">数据库: <span id="dbNameDisplay">-</span> | 端口 9001</div>
                </div>
                
                <div class="container">
                    <div class="stats-grid" id="statsGrid">
                        <div class="stat-card">
                            <div class="stat-value" id="bucketCount">0</div>
                            <div class="stat-label">存储桶</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-value" id="fileCount">0</div>
                            <div class="stat-label">文件总数</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-value" id="totalSize">0 B</div>
                            <div class="stat-label">总大小</div>
                        </div>
                    </div>
                    
                    <div class="card">
                        <h2>存储桶管理</h2>
                        <button class="btn" onclick="showCreateBucketModal()">创建存储桶</button>
                        <button class="btn" onclick="loadBuckets()">刷新列表</button>
                        <div class="bucket-list" id="bucketList" style="margin-top: 20px;"></div>
                    </div>
                    
                    <div class="card hidden" id="fileManager">
                        <h2>文件管理 - <span id="currentBucket"></span><span id="defaultBadge" class="default-badge hidden">默认</span></h2>
                        <div class="category-tabs" id="categoryTabs"></div>
                        
                        <div class="upload-area" id="uploadArea">
                            <p>拖拽文件到此处或点击上传</p>
                            <input type="file" id="fileInput" multiple>
                            <button class="btn" onclick="document.getElementById('fileInput').click()">选择文件</button>
                        </div>
                        
                        <ul class="file-list" id="fileList"></ul>
                    </div>
                </div>
                
                <div class="modal" id="createBucketModal">
                    <div class="modal-content">
                        <h3>创建存储桶</h3>
                        <input type="text" id="newBucketName" placeholder="输入存储桶名称">
                        <button class="btn" onclick="createBucket()">创建</button>
                        <button class="btn btn-danger" onclick="hideCreateBucketModal()">取消</button>
                    </div>
                </div>
                
                <script>
                    let currentBucket = '';
                    let currentCategory = '';
                    let databaseName = '';
                    
                    async function loadBuckets() {
                        const res = await fetch('/buckets');
                        const data = await res.json();
                        databaseName = data.databaseName;
                        document.getElementById('dbNameDisplay').textContent = databaseName;
                        document.getElementById('bucketCount').textContent = data.buckets.length;
                        
                        let html = '';
                        data.buckets.forEach(b => {
                            const defaultClass = b.isDefault ? 'default' : '';
                            const defaultBadge = b.isDefault ? '<span class="default-badge">默认</span>' : '';
                            html += '<div class="bucket-item ' + defaultClass + '" onclick="selectBucket(\\'' + b.name + '\\', ' + b.isDefault + ')">' +
                                '<div class="bucket-name">' + b.name + defaultBadge + '</div>' +
                                '<div class="bucket-stats">' + b.fileCount + ' 个文件 · ' + formatSize(b.totalSize) + '</div>' +
                                '</div>';
                        });
                        document.getElementById('bucketList').innerHTML = html || '<p>暂无存储桶</p>';
                        loadStats();
                    }
                    
                    async function loadStats() {
                        const res = await fetch('/status');
                        const data = await res.json();
                        document.getElementById('fileCount').textContent = data.totalFiles;
                        document.getElementById('totalSize').textContent = formatSize(data.totalSize);
                    }
                    
                    async function selectBucket(name, isDefault) {
                        currentBucket = name;
                        document.getElementById('currentBucket').textContent = name;
                        document.getElementById('defaultBadge').classList.toggle('hidden', !isDefault);
                        document.getElementById('fileManager').classList.remove('hidden');
                        loadCategories();
                        loadFiles();
                    }
                    
                    async function loadCategories() {
                        const res = await fetch('/categories');
                        const data = await res.json();
                        let html = '<div class="category-tab active" onclick="loadFiles()">全部</div>';
                        data.categories.forEach(c => {
                            html += '<div class="category-tab" onclick="loadFilesByCategory(\\'' + c.name + '\\')">' + c.displayName + '</div>';
                        });
                        document.getElementById('categoryTabs').innerHTML = html;
                    }
                    
                    async function loadFiles() {
                        currentCategory = '';
                        const res = await fetch('/buckets/' + currentBucket + '/files');
                        const data = await res.json();
                        renderFiles(data.files);
                    }
                    
                    async function loadFilesByCategory(category) {
                        currentCategory = category;
                        const res = await fetch('/buckets/' + currentBucket + '/files/' + category);
                        const data = await res.json();
                        renderFiles(data.files);
                    }
                    
                    function renderFiles(files) {
                        let html = '';
                        files.forEach(f => {
                            html += '<li class="file-item">' +
                                '<div class="file-icon">' + getCategoryIcon(f.category) + '</div>' +
                                '<div class="file-info">' +
                                '<div class="file-name">' + f.objectName + '</div>' +
                                '<div class="file-meta">' + formatSize(f.size) + ' · ' + f.categoryDir + '</div>' +
                                '</div>' +
                                '<button class="btn" onclick="downloadFile(\\'' + f.objectId + '\\', \\'' + f.objectName + '\\')">下载</button>' +
                                '<button class="btn btn-danger" onclick="deleteFile(\\'' + f.objectId + '\\')">删除</button>' +
                                '</li>';
                        });
                        document.getElementById('fileList').innerHTML = html || '<li class="file-item">暂无文件</li>';
                    }
                    
                    function getCategoryIcon(category) {
                        const icons = { IMAGE: '🖼', VIDEO: '🎬', AUDIO: '🎵', DOCUMENT: '📄', ARCHIVE: '📦', APPLICATION: '⚙', OTHER: '📁' };
                        return icons[category] || '📁';
                    }
                    
                    function formatSize(bytes) {
                        if (bytes < 1024) return bytes + ' B';
                        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB';
                        if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(2) + ' MB';
                        return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
                    }
                    
                    function showCreateBucketModal() {
                        document.getElementById('createBucketModal').style.display = 'block';
                    }
                    
                    function hideCreateBucketModal() {
                        document.getElementById('createBucketModal').style.display = 'none';
                        document.getElementById('newBucketName').value = '';
                    }
                    
                    async function createBucket() {
                        const name = document.getElementById('newBucketName').value.trim();
                        if (!name) return alert('请输入存储桶名称');
                        
                        const res = await fetch('/buckets', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ name })
                        });
                        const data = await res.json();
                        if (data.success) {
                            hideCreateBucketModal();
                            loadBuckets();
                        } else {
                            alert(data.error);
                        }
                    }
                    
                    async function uploadFile(file) {
                        const formData = new FormData();
                        formData.append('file', file);
                        
                        const res = await fetch('/buckets/' + currentBucket + '/upload', {
                            method: 'POST',
                            body: formData
                        });
                        const data = await res.json();
                        if (data.success) {
                            currentCategory ? loadFilesByCategory(currentCategory) : loadFiles();
                            loadStats();
                        } else {
                            alert(data.error);
                        }
                    }
                    
                    function downloadFile(objectId, filename) {
                        const a = document.createElement('a');
                        a.href = '/buckets/' + currentBucket + '/download/' + objectId;
                        a.download = filename;
                        a.click();
                    }
                    
                    async function deleteFile(objectId) {
                        if (!confirm('确定要删除此文件吗？')) return;
                        
                        const res = await fetch('/buckets/' + currentBucket + '/' + objectId, { method: 'DELETE' });
                        const data = await res.json();
                        if (data.success) {
                            currentCategory ? loadFilesByCategory(currentCategory) : loadFiles();
                            loadStats();
                        } else {
                            alert(data.error);
                        }
                    }
                    
                    document.getElementById('fileInput').addEventListener('change', (e) => {
                        Array.from(e.target.files).forEach(uploadFile);
                        e.target.value = '';
                    });
                    
                    document.getElementById('uploadArea').addEventListener('dragover', (e) => {
                        e.preventDefault();
                        e.currentTarget.style.borderColor = '#667eea';
                    });
                    
                    document.getElementById('uploadArea').addEventListener('dragleave', (e) => {
                        e.currentTarget.style.borderColor = '#ddd';
                    });
                    
                    document.getElementById('uploadArea').addEventListener('drop', (e) => {
                        e.preventDefault();
                        e.currentTarget.style.borderColor = '#ddd';
                        Array.from(e.dataTransfer.files).forEach(uploadFile);
                    });
                    
                    loadBuckets();
                </script>
            </body>
            </html>
            """;
    }
    
    /**
     * 是否运行中
     */
    public boolean isRunning() {
        return running;
    }

    public Map<String, StorageBucket> getBuckets() {
        return buckets;
    }

    /**
     * 获取存储桶
     */
    public StorageBucket getBucket(String name) {
        return buckets.get(name);
    }

    /**
     * 获取数据库名
     */
    public String getDatabaseName() {
        return databaseName;
    }
}
