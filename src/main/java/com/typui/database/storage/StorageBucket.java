package com.typui.database.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储桶类
 * 类似MinIO的Bucket概念
 * 支持嵌套式目录结构：bucket/images/jpg/、bucket/images/png/
 */
public class StorageBucket {
    
    private static final Logger logger = LoggerFactory.getLogger(StorageBucket.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final Map<String, String> EXTENSION_TO_CATEGORY = new HashMap<>();
    
    static {
        EXTENSION_TO_CATEGORY.put("jpg", "images");
        EXTENSION_TO_CATEGORY.put("jpeg", "images");
        EXTENSION_TO_CATEGORY.put("png", "images");
        EXTENSION_TO_CATEGORY.put("gif", "images");
        EXTENSION_TO_CATEGORY.put("bmp", "images");
        EXTENSION_TO_CATEGORY.put("webp", "images");
        EXTENSION_TO_CATEGORY.put("svg", "images");
        EXTENSION_TO_CATEGORY.put("ico", "images");
        EXTENSION_TO_CATEGORY.put("tiff", "images");
        EXTENSION_TO_CATEGORY.put("tif", "images");
        EXTENSION_TO_CATEGORY.put("heic", "images");
        EXTENSION_TO_CATEGORY.put("heif", "images");
        EXTENSION_TO_CATEGORY.put("raw", "images");
        EXTENSION_TO_CATEGORY.put("psd", "images");
        EXTENSION_TO_CATEGORY.put("ai", "images");
        
        EXTENSION_TO_CATEGORY.put("mp4", "videos");
        EXTENSION_TO_CATEGORY.put("avi", "videos");
        EXTENSION_TO_CATEGORY.put("mkv", "videos");
        EXTENSION_TO_CATEGORY.put("mov", "videos");
        EXTENSION_TO_CATEGORY.put("wmv", "videos");
        EXTENSION_TO_CATEGORY.put("flv", "videos");
        EXTENSION_TO_CATEGORY.put("webm", "videos");
        EXTENSION_TO_CATEGORY.put("m4v", "videos");
        EXTENSION_TO_CATEGORY.put("3gp", "videos");
        EXTENSION_TO_CATEGORY.put("ts", "videos");
        
        EXTENSION_TO_CATEGORY.put("mp3", "audio");
        EXTENSION_TO_CATEGORY.put("wav", "audio");
        EXTENSION_TO_CATEGORY.put("flac", "audio");
        EXTENSION_TO_CATEGORY.put("aac", "audio");
        EXTENSION_TO_CATEGORY.put("ogg", "audio");
        EXTENSION_TO_CATEGORY.put("wma", "audio");
        EXTENSION_TO_CATEGORY.put("m4a", "audio");
        EXTENSION_TO_CATEGORY.put("ape", "audio");
        EXTENSION_TO_CATEGORY.put("amr", "audio");
        
        EXTENSION_TO_CATEGORY.put("pdf", "documents");
        EXTENSION_TO_CATEGORY.put("doc", "documents");
        EXTENSION_TO_CATEGORY.put("docx", "documents");
        EXTENSION_TO_CATEGORY.put("xls", "documents");
        EXTENSION_TO_CATEGORY.put("xlsx", "documents");
        EXTENSION_TO_CATEGORY.put("ppt", "documents");
        EXTENSION_TO_CATEGORY.put("pptx", "documents");
        EXTENSION_TO_CATEGORY.put("txt", "documents");
        EXTENSION_TO_CATEGORY.put("rtf", "documents");
        EXTENSION_TO_CATEGORY.put("odt", "documents");
        EXTENSION_TO_CATEGORY.put("ods", "documents");
        EXTENSION_TO_CATEGORY.put("odp", "documents");
        
        EXTENSION_TO_CATEGORY.put("zip", "archives");
        EXTENSION_TO_CATEGORY.put("rar", "archives");
        EXTENSION_TO_CATEGORY.put("7z", "archives");
        EXTENSION_TO_CATEGORY.put("tar", "archives");
        EXTENSION_TO_CATEGORY.put("gz", "archives");
        EXTENSION_TO_CATEGORY.put("bz2", "archives");
        EXTENSION_TO_CATEGORY.put("xz", "archives");
        
        EXTENSION_TO_CATEGORY.put("apk", "applications");
        EXTENSION_TO_CATEGORY.put("ipa", "applications");
        EXTENSION_TO_CATEGORY.put("exe", "applications");
        EXTENSION_TO_CATEGORY.put("msi", "applications");
        EXTENSION_TO_CATEGORY.put("dmg", "applications");
        EXTENSION_TO_CATEGORY.put("deb", "applications");
        EXTENSION_TO_CATEGORY.put("rpm", "applications");
        EXTENSION_TO_CATEGORY.put("iso", "applications");
        EXTENSION_TO_CATEGORY.put("jar", "applications");
        EXTENSION_TO_CATEGORY.put("war", "applications");
        EXTENSION_TO_CATEGORY.put("ear", "applications");
    }
    
    private final String name;
    private final File bucketDir;
    private final File metadataDir;
    private final Map<String, FileMetadata> metadataCache;
    private final Map<String, String> idIndex;
    private final Set<String> usedIds;
    private final com.typui.database.security.DataEncryptor encryptor;
    private boolean publicRead;
    
    public StorageBucket(String name, File dataDir) {
        this.name = name;
        this.bucketDir = new File(dataDir, name);
        this.metadataDir = new File(bucketDir, ".metadata");
        this.metadataCache = new ConcurrentHashMap<>();
        this.idIndex = new ConcurrentHashMap<>();
        this.usedIds = ConcurrentHashMap.newKeySet();
        this.publicRead = true;
        String baseKey = System.getProperty("typui.storage.encryption.key",
                "typui-storage-default-key-2024");
        this.encryptor = new com.typui.database.security.DataEncryptor(
                baseKey + "|" + dataDir.getAbsolutePath() + "|" + name);
        
        initializeBucket();
    }
    
    /**
     * 初始化存储桶
     * 创建嵌套式目录结构
     */
    private void initializeBucket() {
        if (!bucketDir.exists()) {
            bucketDir.mkdirs();
            logger.info("存储桶目录创建成功: {}", bucketDir.getAbsolutePath());
        }
        
        if (!metadataDir.exists()) {
            metadataDir.mkdirs();
        }
        
        String[] categories = {"images", "videos", "audio", "documents", "archives", "applications", "others"};
        for (String category : categories) {
            File categoryDir = new File(bucketDir, category);
            if (!categoryDir.exists()) {
                categoryDir.mkdirs();
            }
        }
        
        loadMetadata();
    }
    
    /**
     * 加载元数据并建立ID索引
     */
    @SuppressWarnings("unchecked")
    private void loadMetadata() {
        File[] files = metadataDir.listFiles((dir, filename) -> filename.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                try {
                    Map<String, Object> data = objectMapper.readValue(file, Map.class);
                    FileMetadata metadata = FileMetadata.fromMap(data);
                    String objectId = metadata.getObjectId();
                    
                    if (usedIds.contains(objectId)) {
                        logger.warn("检测到重复ID: {}, 文件: {}", objectId, metadata.getObjectName());
                        continue;
                    }
                    
                    metadataCache.put(objectId, metadata);
                    usedIds.add(objectId);
                    
                    String indexKey = metadata.getObjectName().toLowerCase();
                    idIndex.put(indexKey, objectId);
                    
                } catch (IOException e) {
                    logger.error("加载元数据失败: {}", file.getName(), e);
                }
            }
        }
        logger.info("存储桶 {} 元数据加载完成，共 {} 个文件", name, metadataCache.size());
    }
    
    /**
     * 生成全局唯一ID（带判重）
     */
    private String generateUniqueId() {
        String id;
        int attempts = 0;
        do {
            id = UUID.randomUUID().toString().replace("-", "");
            attempts++;
            if (attempts > 100) {
                id = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            }
        } while (usedIds.contains(id));
        
        usedIds.add(id);
        return id;
    }
    
    /**
     * 检查ID是否已存在
     */
    public boolean isIdExists(String id) {
        return usedIds.contains(id);
    }
    
    /**
     * 计算文件哈希
     */
    private String calculateHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
    
    /**
     * 获取分类目录名
     */
    private String getCategoryName(String extension) {
        return EXTENSION_TO_CATEGORY.getOrDefault(extension, "others");
    }
    
    /**
     * 获取MIME类型
     */
    private String getMimeType(String extension) {
        return FileTypeClassifier.getMimeType(extension);
    }
    
    /**
     * 上传文件
     * 自动按扩展名创建嵌套目录
     */
    public FileMetadata upload(String objectName, byte[] data, String contentType, String uploader) {
        String extension = getFileExtension(objectName);
        String objectId = generateUniqueId();

        String categoryName = getCategoryName(extension);
        // 加密文件以 .enc 结尾（无法用记事本直接查看/修改）
        String storedName = objectId + (extension.isEmpty() ? "" : "." + extension) + ".enc";

        File extensionDir = new File(new File(bucketDir, categoryName),
                extension.isEmpty() ? "unknown" : extension);
        if (!extensionDir.exists()) {
            extensionDir.mkdirs();
            logger.info("创建扩展名目录: {}", extensionDir.getAbsolutePath());
        }

        File targetFile = new File(extensionDir, storedName);
        try {
            // 把原始字节 base64 编码 → 当作字符串 → AES-256-GCM 加密
            String b64 = java.util.Base64.getEncoder().encodeToString(data);
            String encrypted = encryptor.encrypt(b64);
            java.nio.file.Files.write(targetFile.toPath(),
                    encrypted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            usedIds.remove(objectId);
            logger.error("文件写入失败: {}", objectName, e);
            throw new RuntimeException("文件写入失败: " + e.getMessage());
        }

        if (contentType == null || contentType.isEmpty()) {
            contentType = getMimeType(extension);
        }

        String hash = calculateHash(data);

        FileMetadata metadata = new FileMetadata();
        metadata.setObjectId(objectId);
        metadata.setObjectName(objectName);
        metadata.setStoredName(storedName);
        metadata.setContentType(contentType);
        metadata.setSize(data.length);
        metadata.setHash(hash);
        metadata.setCategory(categoryName);
        metadata.setCategoryDir(categoryName + "/" + (extension.isEmpty() ? "unknown" : extension));
        metadata.setExtension(extension);
        metadata.setUploader(uploader);
        metadata.setCreatedAt(System.currentTimeMillis());
        metadata.setUpdatedAt(System.currentTimeMillis());
        metadata.setPublicRead(publicRead);

        metadataCache.put(objectId, metadata);
        idIndex.put(objectName.toLowerCase(), objectId);
        saveMetadata(objectId, metadata);

        logger.info("文件上传（已加密）成功: {} -> {}/{}", objectName, categoryName, extension);

        return metadata;
    }
    
    /**
     * 通过ID下载文件
     */
    public byte[] download(String objectId) {
        FileMetadata metadata = metadataCache.get(objectId);
        if (metadata == null) {
            return null;
        }

        File file = new File(bucketDir, metadata.getCategoryDir() + File.separator + metadata.getStoredName());
        if (!file.exists()) {
            logger.warn("文件不存在: {}", file.getAbsolutePath());
            return null;
        }

        try {
            if (file.getName().endsWith(".enc")) {
                String encrypted = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                String decrypted = encryptor.decrypt(encrypted);
                return java.util.Base64.getDecoder().decode(decrypted);
            } else {
                // 旧的明文文件，兼容读取
                return java.nio.file.Files.readAllBytes(file.toPath());
            }
        } catch (IOException e) {
            logger.error("文件读取失败: {}", objectId, e);
            return null;
        }
    }
    
    /**
     * 通过文件名下载文件
     */
    public byte[] downloadByName(String objectName) {
        String objectId = idIndex.get(objectName.toLowerCase());
        if (objectId == null) {
            return null;
        }
        return download(objectId);
    }
    
    /**
     * 获取文件流
     */
    public InputStream getStream(String objectId) {
        byte[] data = download(objectId);
        if (data == null) return null;
        return new java.io.ByteArrayInputStream(data);
    }
    
    /**
     * 删除文件
     */
    public boolean delete(String objectId) {
        FileMetadata metadata = metadataCache.remove(objectId);
        if (metadata == null) {
            return false;
        }
        
        File file = new File(bucketDir, metadata.getCategoryDir() + File.separator + metadata.getStoredName());
        if (file.exists()) {
            file.delete();
        }
        
        File metaFile = new File(metadataDir, objectId + ".json");
        if (metaFile.exists()) {
            metaFile.delete();
        }
        
        usedIds.remove(objectId);
        idIndex.remove(metadata.getObjectName().toLowerCase());
        
        logger.info("文件删除成功: {}", objectId);
        return true;
    }
    
    /**
     * 检查文件是否存在
     */
    public boolean exists(String objectId) {
        return metadataCache.containsKey(objectId);
    }
    
    /**
     * 通过文件名检查是否存在
     */
    public boolean existsByName(String objectName) {
        return idIndex.containsKey(objectName.toLowerCase());
    }
    
    /**
     * 获取文件元数据
     */
    public FileMetadata getMetadata(String objectId) {
        return metadataCache.get(objectId);
    }
    
    /**
     * 通过文件名获取元数据
     */
    public FileMetadata getMetadataByName(String objectName) {
        String objectId = idIndex.get(objectName.toLowerCase());
        if (objectId == null) {
            return null;
        }
        return metadataCache.get(objectId);
    }
    
    /**
     * 列出所有文件
     */
    public List<FileMetadata> listAll() {
        return new ArrayList<>(metadataCache.values());
    }
    
    /**
     * 按分类列出文件
     */
    public List<FileMetadata> listByCategory(String category) {
        List<FileMetadata> result = new ArrayList<>();
        for (FileMetadata metadata : metadataCache.values()) {
            if (metadata.getCategory().equalsIgnoreCase(category)) {
                result.add(metadata);
            }
        }
        return result;
    }
    
    /**
     * 按扩展名列出文件
     */
    public List<FileMetadata> listByExtension(String extension) {
        List<FileMetadata> result = new ArrayList<>();
        String ext = extension.toLowerCase().replace(".", "");
        for (FileMetadata metadata : metadataCache.values()) {
            if (ext.equals(metadata.getExtension())) {
                result.add(metadata);
            }
        }
        return result;
    }
    
    /**
     * 按上传者列出文件
     */
    public List<FileMetadata> listByUploader(String uploader) {
        List<FileMetadata> result = new ArrayList<>();
        for (FileMetadata metadata : metadataCache.values()) {
            if (uploader.equals(metadata.getUploader())) {
                result.add(metadata);
            }
        }
        return result;
    }
    
    /**
     * 搜索文件
     */
    public List<FileMetadata> search(String keyword) {
        List<FileMetadata> result = new ArrayList<>();
        String lowerKeyword = keyword.toLowerCase();
        
        for (FileMetadata metadata : metadataCache.values()) {
            if (metadata.getObjectName().toLowerCase().contains(lowerKeyword)) {
                result.add(metadata);
            }
        }
        
        return result;
    }
    
    /**
     * 获取存储桶大小
     */
    public long getTotalSize() {
        long total = 0;
        for (FileMetadata metadata : metadataCache.values()) {
            total += metadata.getSize();
        }
        return total;
    }
    
    /**
     * 获取文件数量
     */
    public int getFileCount() {
        return metadataCache.size();
    }
    
    /**
     * 设置公开读取
     */
    public void setPublicRead(boolean publicRead) {
        this.publicRead = publicRead;
    }
    
    /**
     * 是否公开读取
     */
    public boolean isPublicRead() {
        return publicRead;
    }
    
    /**
     * 获取存储桶名称
     */
    public String getName() {
        return name;
    }
    
    /**
     * 清空存储桶
     */
    public void clear() {
        String[] categories = {"images", "videos", "audio", "documents", "archives", "applications", "others"};
        for (String category : categories) {
            File categoryDir = new File(bucketDir, category);
            deleteDirectory(categoryDir);
        }
        
        File[] metaFiles = metadataDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (metaFiles != null) {
            for (File file : metaFiles) {
                file.delete();
            }
        }
        
        metadataCache.clear();
        idIndex.clear();
        usedIds.clear();
        logger.info("存储桶已清空: {}", name);
    }
    
    /**
     * 递归删除目录
     */
    private void deleteDirectory(File dir) {
        if (dir.exists()) {
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
    }
    
    /**
     * 保存元数据
     */
    private void saveMetadata(String objectId, FileMetadata metadata) {
        File metaFile = new File(metadataDir, objectId + ".json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaFile, metadata.toMap());
        } catch (IOException e) {
            logger.error("保存元数据失败: {}", objectId, e);
        }
    }
    
    /**
     * 获取文件物理路径
     */
    public String getFilePath(String objectId) {
        FileMetadata metadata = metadataCache.get(objectId);
        if (metadata == null) {
            return null;
        }
        return bucketDir.getAbsolutePath() + File.separator + metadata.getCategoryDir() + File.separator + metadata.getStoredName();
    }
    
    /**
     * 获取目录结构
     */
    public Map<String, Object> getDirectoryStructure() {
        Map<String, Object> structure = new LinkedHashMap<>();
        String[] categories = {"images", "videos", "audio", "documents", "archives", "applications", "others"};
        
        for (String category : categories) {
            File categoryDir = new File(bucketDir, category);
            if (categoryDir.exists()) {
                Map<String, Integer> extensions = new LinkedHashMap<>();
                File[] extDirs = categoryDir.listFiles(File::isDirectory);
                if (extDirs != null) {
                    for (File extDir : extDirs) {
                        File[] files = extDir.listFiles(File::isFile);
                        extensions.put(extDir.getName(), files != null ? files.length : 0);
                    }
                }
                structure.put(category, extensions);
            }
        }
        
        return structure;
    }
}
