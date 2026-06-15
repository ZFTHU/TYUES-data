package com.typui.database.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分块上传管理器
 * 支持断点续传、大文件分块上传
 */
public class ChunkUploadManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ChunkUploadManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024; // 5MB
    
    private final File uploadDir;
    private final File tempDir;
    private final Map<String, UploadSession> sessions;
    private final int chunkSize;
    
    public ChunkUploadManager(String dataDir, int chunkSize) {
        this.uploadDir = new File(dataDir, "uploads");
        this.tempDir = new File(dataDir, "temp");
        this.sessions = new ConcurrentHashMap<>();
        this.chunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        
        initializeDirs();
        loadSessions();
    }
    
    /**
     * 初始化目录
     */
    private void initializeDirs() {
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
    }
    
    /**
     * 加载上传会话
     */
    @SuppressWarnings("unchecked")
    private void loadSessions() {
        File[] files = uploadDir.listFiles((dir, name) -> name.endsWith(".session"));
        if (files != null) {
            for (File file : files) {
                try {
                    Map<String, Object> data = objectMapper.readValue(file, Map.class);
                    UploadSession session = UploadSession.fromMap(data);
                    sessions.put(session.getUploadId(), session);
                } catch (Exception e) {
                    logger.error("加载上传会话失败: {}", file.getName(), e);
                }
            }
        }
        logger.info("上传会话加载完成，共 {} 个", sessions.size());
    }
    
    /**
     * 初始化分块上传
     */
    public UploadSession initUpload(String fileName, long fileSize, String contentType, 
                                    String bucket, String uploader) {
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        
        UploadSession session = new UploadSession();
        session.setUploadId(uploadId);
        session.setFileName(fileName);
        session.setFileSize(fileSize);
        session.setContentType(contentType);
        session.setBucket(bucket);
        session.setUploader(uploader);
        session.setChunkSize(chunkSize);
        session.setTotalChunks((int) Math.ceil((double) fileSize / chunkSize));
        session.setUploadedChunks(new HashSet<>());
        session.setCreatedAt(System.currentTimeMillis());
        session.setStatus("pending");
        
        File sessionDir = new File(tempDir, uploadId);
        sessionDir.mkdirs();
        
        sessions.put(uploadId, session);
        saveSession(session);
        
        logger.info("分块上传初始化: {} - {} ({} chunks)", uploadId, fileName, session.getTotalChunks());
        
        return session;
    }
    
    /**
     * 上传分块
     */
    public boolean uploadChunk(String uploadId, int chunkIndex, byte[] data, String hash) {
        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            throw new RuntimeException("上传会话不存在: " + uploadId);
        }
        
        if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
            throw new RuntimeException("无效的分块索引: " + chunkIndex);
        }
        
        if (hash != null && !hash.isEmpty()) {
            String actualHash = calculateHash(data);
            if (!hash.equals(actualHash)) {
                throw new RuntimeException("分块哈希校验失败");
            }
        }
        
        try {
            File chunkFile = new File(new File(tempDir, uploadId), 
                String.format("chunk_%05d", chunkIndex));
            Files.write(chunkFile.toPath(), data);
            
            session.getUploadedChunks().add(chunkIndex);
            saveSession(session);
            
            logger.debug("分块上传成功: {} - chunk {}/{}", uploadId, chunkIndex + 1, session.getTotalChunks());
            
            return true;
        } catch (Exception e) {
            logger.error("分块上传失败: {} - chunk {}", uploadId, chunkIndex, e);
            throw new RuntimeException("分块上传失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 完成上传
     */
    public String completeUpload(String uploadId) {
        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            throw new RuntimeException("上传会话不存在: " + uploadId);
        }
        
        if (session.getUploadedChunks().size() != session.getTotalChunks()) {
            throw new RuntimeException("分块未全部上传完成");
        }
        
        try {
            File outputFile = new File(new File(tempDir, uploadId), session.getFileName());
            
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                for (int i = 0; i < session.getTotalChunks(); i++) {
                    File chunkFile = new File(new File(tempDir, uploadId), 
                        String.format("chunk_%05d", i));
                    Files.copy(chunkFile.toPath(), fos);
                    chunkFile.delete();
                }
            }
            
            String fileHash = calculateFileHash(outputFile);
            session.setFileHash(fileHash);
            session.setStatus("completed");
            session.setCompletedAt(System.currentTimeMillis());
            saveSession(session);
            
            logger.info("分块上传完成: {} - {}", uploadId, session.getFileName());
            
            return outputFile.getAbsolutePath();
        } catch (Exception e) {
            logger.error("完成上传失败: {}", uploadId, e);
            throw new RuntimeException("完成上传失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 取消上传
     */
    public void abortUpload(String uploadId) {
        UploadSession session = sessions.remove(uploadId);
        if (session != null) {
            File sessionDir = new File(tempDir, uploadId);
            deleteDirectory(sessionDir);
            
            File sessionFile = new File(uploadDir, uploadId + ".session");
            if (sessionFile.exists()) {
                sessionFile.delete();
            }
            
            logger.info("上传已取消: {}", uploadId);
        }
    }
    
    /**
     * 获取上传状态
     */
    public UploadSession getUploadStatus(String uploadId) {
        return sessions.get(uploadId);
    }
    
    /**
     * 列出所有上传会话
     */
    public List<UploadSession> listUploads() {
        return new ArrayList<>(sessions.values());
    }
    
    /**
     * 清理过期会话
     */
    public void cleanupExpiredSessions(long expireMs) {
        long now = System.currentTimeMillis();
        
        sessions.entrySet().removeIf(entry -> {
            UploadSession session = entry.getValue();
            if (now - session.getCreatedAt() > expireMs && 
                !"completed".equals(session.getStatus())) {
                
                File sessionDir = new File(tempDir, entry.getKey());
                deleteDirectory(sessionDir);
                
                File sessionFile = new File(uploadDir, entry.getKey() + ".session");
                if (sessionFile.exists()) {
                    sessionFile.delete();
                }
                
                logger.info("清理过期上传会话: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
    
    /**
     * 保存会话
     */
    private void saveSession(UploadSession session) {
        try {
            File sessionFile = new File(uploadDir, session.getUploadId() + ".session");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(sessionFile, session.toMap());
        } catch (Exception e) {
            logger.error("保存上传会话失败: {}", session.getUploadId(), e);
        }
    }
    
    /**
     * 计算哈希
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
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 计算文件哈希
     */
    private String calculateFileHash(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 删除目录
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
     * 上传会话类
     */
    public static class UploadSession {
        private String uploadId;
        private String fileName;
        private long fileSize;
        private String contentType;
        private String bucket;
        private String uploader;
        private int chunkSize;
        private int totalChunks;
        private Set<Integer> uploadedChunks;
        private long createdAt;
        private long completedAt;
        private String status;
        private String fileHash;
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("uploadId", uploadId);
            map.put("fileName", fileName);
            map.put("fileSize", fileSize);
            map.put("contentType", contentType);
            map.put("bucket", bucket);
            map.put("uploader", uploader);
            map.put("chunkSize", chunkSize);
            map.put("totalChunks", totalChunks);
            map.put("uploadedChunks", uploadedChunks);
            map.put("createdAt", createdAt);
            map.put("completedAt", completedAt);
            map.put("status", status);
            map.put("fileHash", fileHash);
            return map;
        }
        
        public static UploadSession fromMap(Map<String, Object> map) {
            UploadSession session = new UploadSession();
            session.setUploadId((String) map.get("uploadId"));
            session.setFileName((String) map.get("fileName"));
            session.setFileSize(map.get("fileSize") != null ? ((Number) map.get("fileSize")).longValue() : 0);
            session.setContentType((String) map.get("contentType"));
            session.setBucket((String) map.get("bucket"));
            session.setUploader((String) map.get("uploader"));
            session.setChunkSize(map.get("chunkSize") != null ? ((Number) map.get("chunkSize")).intValue() : 0);
            session.setTotalChunks(map.get("totalChunks") != null ? ((Number) map.get("totalChunks")).intValue() : 0);
            
            @SuppressWarnings("unchecked")
            List<Number> chunks = (List<Number>) map.get("uploadedChunks");
            Set<Integer> uploadedSet = new HashSet<>();
            if (chunks != null) {
                for (Number n : chunks) {
                    uploadedSet.add(n.intValue());
                }
            }
            session.setUploadedChunks(uploadedSet);
            
            session.setCreatedAt(map.get("createdAt") != null ? ((Number) map.get("createdAt")).longValue() : 0);
            session.setCompletedAt(map.get("completedAt") != null ? ((Number) map.get("completedAt")).longValue() : 0);
            session.setStatus((String) map.get("status"));
            session.setFileHash((String) map.get("fileHash"));
            
            return session;
        }
        
        public int getProgress() {
            if (totalChunks == 0) return 0;
            return (int) ((uploadedChunks.size() * 100.0) / totalChunks);
        }
        
        public String getUploadId() { return uploadId; }
        public void setUploadId(String uploadId) { this.uploadId = uploadId; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getUploader() { return uploader; }
        public void setUploader(String uploader) { this.uploader = uploader; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getTotalChunks() { return totalChunks; }
        public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }
        public Set<Integer> getUploadedChunks() { return uploadedChunks; }
        public void setUploadedChunks(Set<Integer> uploadedChunks) { this.uploadedChunks = uploadedChunks; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public long getCompletedAt() { return completedAt; }
        public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getFileHash() { return fileHash; }
        public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    }
}
