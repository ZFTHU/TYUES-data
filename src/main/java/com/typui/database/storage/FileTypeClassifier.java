package com.typui.database.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 文件类型分类器
 * 用于自动分类存储不同类型的文件
 */
public class FileTypeClassifier {
    
    private static final Map<String, String> IMAGE_TYPES = new HashMap<>();
    private static final Map<String, String> VIDEO_TYPES = new HashMap<>();
    private static final Map<String, String> AUDIO_TYPES = new HashMap<>();
    private static final Map<String, String> DOCUMENT_TYPES = new HashMap<>();
    private static final Map<String, String> ARCHIVE_TYPES = new HashMap<>();
    private static final Map<String, String> APPLICATION_TYPES = new HashMap<>();
    
    static {
        IMAGE_TYPES.put("jpg", "image/jpeg");
        IMAGE_TYPES.put("jpeg", "image/jpeg");
        IMAGE_TYPES.put("png", "image/png");
        IMAGE_TYPES.put("gif", "image/gif");
        IMAGE_TYPES.put("bmp", "image/bmp");
        IMAGE_TYPES.put("webp", "image/webp");
        IMAGE_TYPES.put("svg", "image/svg+xml");
        IMAGE_TYPES.put("ico", "image/x-icon");
        IMAGE_TYPES.put("tiff", "image/tiff");
        IMAGE_TYPES.put("tif", "image/tiff");
        IMAGE_TYPES.put("heic", "image/heic");
        IMAGE_TYPES.put("heif", "image/heif");
        IMAGE_TYPES.put("raw", "image/raw");
        IMAGE_TYPES.put("psd", "image/vnd.adobe.photoshop");
        IMAGE_TYPES.put("ai", "application/postscript");
        
        VIDEO_TYPES.put("mp4", "video/mp4");
        VIDEO_TYPES.put("avi", "video/x-msvideo");
        VIDEO_TYPES.put("mkv", "video/x-matroska");
        VIDEO_TYPES.put("mov", "video/quicktime");
        VIDEO_TYPES.put("wmv", "video/x-ms-wmv");
        VIDEO_TYPES.put("flv", "video/x-flv");
        VIDEO_TYPES.put("webm", "video/webm");
        VIDEO_TYPES.put("m4v", "video/mp4");
        VIDEO_TYPES.put("3gp", "video/3gpp");
        VIDEO_TYPES.put("ts", "video/mp2t");
        
        AUDIO_TYPES.put("mp3", "audio/mpeg");
        AUDIO_TYPES.put("wav", "audio/wav");
        AUDIO_TYPES.put("flac", "audio/flac");
        AUDIO_TYPES.put("aac", "audio/aac");
        AUDIO_TYPES.put("ogg", "audio/ogg");
        AUDIO_TYPES.put("wma", "audio/x-ms-wma");
        AUDIO_TYPES.put("m4a", "audio/mp4");
        AUDIO_TYPES.put("ape", "audio/ape");
        AUDIO_TYPES.put("amr", "audio/amr");
        
        DOCUMENT_TYPES.put("pdf", "application/pdf");
        DOCUMENT_TYPES.put("doc", "application/msword");
        DOCUMENT_TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        DOCUMENT_TYPES.put("xls", "application/vnd.ms-excel");
        DOCUMENT_TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        DOCUMENT_TYPES.put("ppt", "application/vnd.ms-powerpoint");
        DOCUMENT_TYPES.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        DOCUMENT_TYPES.put("txt", "text/plain");
        DOCUMENT_TYPES.put("rtf", "application/rtf");
        DOCUMENT_TYPES.put("odt", "application/vnd.oasis.opendocument.text");
        DOCUMENT_TYPES.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
        DOCUMENT_TYPES.put("odp", "application/vnd.oasis.opendocument.presentation");
        
        ARCHIVE_TYPES.put("zip", "application/zip");
        ARCHIVE_TYPES.put("rar", "application/x-rar-compressed");
        ARCHIVE_TYPES.put("7z", "application/x-7z-compressed");
        ARCHIVE_TYPES.put("tar", "application/x-tar");
        ARCHIVE_TYPES.put("gz", "application/gzip");
        ARCHIVE_TYPES.put("bz2", "application/x-bzip2");
        ARCHIVE_TYPES.put("xz", "application/x-xz");
        
        APPLICATION_TYPES.put("apk", "application/vnd.android.package-archive");
        APPLICATION_TYPES.put("ipa", "application/octet-stream");
        APPLICATION_TYPES.put("exe", "application/x-msdownload");
        APPLICATION_TYPES.put("msi", "application/x-msi");
        APPLICATION_TYPES.put("dmg", "application/x-apple-diskimage");
        APPLICATION_TYPES.put("deb", "application/x-debian-package");
        APPLICATION_TYPES.put("rpm", "application/x-rpm");
        APPLICATION_TYPES.put("iso", "application/x-iso9660-image");
        APPLICATION_TYPES.put("jar", "application/java-archive");
        APPLICATION_TYPES.put("war", "application/java-archive");
        APPLICATION_TYPES.put("ear", "application/java-archive");
    }
    
    /**
     * 文件分类枚举
     */
    public enum FileCategory {
        IMAGE("images", "图片"),
        VIDEO("videos", "视频"),
        AUDIO("audio", "音频"),
        DOCUMENT("documents", "文档"),
        ARCHIVE("archives", "压缩包"),
        APPLICATION("applications", "应用程序"),
        OTHER("others", "其他");
        
        private final String directory;
        private final String displayName;
        
        FileCategory(String directory, String displayName) {
            this.directory = directory;
            this.displayName = displayName;
        }
        
        public String getDirectory() {
            return directory;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * 根据文件扩展名获取分类
     */
    public static FileCategory getCategory(String extension) {
        if (extension == null) {
            return FileCategory.OTHER;
        }
        
        String ext = extension.toLowerCase().replace(".", "");
        
        if (IMAGE_TYPES.containsKey(ext)) {
            return FileCategory.IMAGE;
        }
        if (VIDEO_TYPES.containsKey(ext)) {
            return FileCategory.VIDEO;
        }
        if (AUDIO_TYPES.containsKey(ext)) {
            return FileCategory.AUDIO;
        }
        if (DOCUMENT_TYPES.containsKey(ext)) {
            return FileCategory.DOCUMENT;
        }
        if (ARCHIVE_TYPES.containsKey(ext)) {
            return FileCategory.ARCHIVE;
        }
        if (APPLICATION_TYPES.containsKey(ext)) {
            return FileCategory.APPLICATION;
        }
        
        return FileCategory.OTHER;
    }
    
    /**
     * 根据文件扩展名获取MIME类型
     */
    public static String getMimeType(String extension) {
        if (extension == null) {
            return "application/octet-stream";
        }
        
        String ext = extension.toLowerCase().replace(".", "");
        
        if (IMAGE_TYPES.containsKey(ext)) {
            return IMAGE_TYPES.get(ext);
        }
        if (VIDEO_TYPES.containsKey(ext)) {
            return VIDEO_TYPES.get(ext);
        }
        if (AUDIO_TYPES.containsKey(ext)) {
            return AUDIO_TYPES.get(ext);
        }
        if (DOCUMENT_TYPES.containsKey(ext)) {
            return DOCUMENT_TYPES.get(ext);
        }
        if (ARCHIVE_TYPES.containsKey(ext)) {
            return ARCHIVE_TYPES.get(ext);
        }
        if (APPLICATION_TYPES.containsKey(ext)) {
            return APPLICATION_TYPES.get(ext);
        }
        
        return "application/octet-stream";
    }
    
    /**
     * 获取所有支持的图片扩展名
     */
    public static Set<String> getImageExtensions() {
        return IMAGE_TYPES.keySet();
    }
    
    /**
     * 获取所有支持的视频扩展名
     */
    public static Set<String> getVideoExtensions() {
        return VIDEO_TYPES.keySet();
    }
    
    /**
     * 获取所有支持的音频扩展名
     */
    public static Set<String> getAudioExtensions() {
        return AUDIO_TYPES.keySet();
    }
    
    /**
     * 获取所有支持的文档扩展名
     */
    public static Set<String> getDocumentExtensions() {
        return DOCUMENT_TYPES.keySet();
    }
    
    /**
     * 获取所有支持的压缩包扩展名
     */
    public static Set<String> getArchiveExtensions() {
        return ARCHIVE_TYPES.keySet();
    }
    
    /**
     * 获取所有支持的应用程序扩展名
     */
    public static Set<String> getApplicationExtensions() {
        return APPLICATION_TYPES.keySet();
    }
    
    /**
     * 检查是否为图片
     */
    public static boolean isImage(String extension) {
        if (extension == null) {
            return false;
        }
        return IMAGE_TYPES.containsKey(extension.toLowerCase().replace(".", ""));
    }
    
    /**
     * 检查是否为视频
     */
    public static boolean isVideo(String extension) {
        if (extension == null) {
            return false;
        }
        return VIDEO_TYPES.containsKey(extension.toLowerCase().replace(".", ""));
    }
    
    /**
     * 检查是否为音频
     */
    public static boolean isAudio(String extension) {
        if (extension == null) {
            return false;
        }
        return AUDIO_TYPES.containsKey(extension.toLowerCase().replace(".", ""));
    }
    
    /**
     * 检查是否为文档
     */
    public static boolean isDocument(String extension) {
        if (extension == null) {
            return false;
        }
        return DOCUMENT_TYPES.containsKey(extension.toLowerCase().replace(".", ""));
    }
    
    /**
     * 检查是否为压缩包
     */
    public static boolean isArchive(String extension) {
        if (extension == null) {
            return false;
        }
        return ARCHIVE_TYPES.containsKey(extension.toLowerCase().replace(".", ""));
    }
    
    /**
     * 检查是否为应用程序
     */
    public static boolean isApplication(String extension) {
        if (extension == null) {
            return false;
        }
        return APPLICATION_TYPES.containsKey(extension.toLowerCase().replace(".", ""));
    }
}
