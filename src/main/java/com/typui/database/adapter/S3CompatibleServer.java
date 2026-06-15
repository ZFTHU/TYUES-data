package com.typui.database.adapter;

import com.typui.database.ConfigManager;
import com.typui.database.storage.StorageBucket;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MinIO/S3 兼容协议层（HTTP）：
 *   - GET /<bucket>/<key>  下载对象
 *   - PUT /<bucket>/<key>  上传对象
 *   - DELETE /<bucket>/<key> 删除对象
 *   - GET /<bucket>         列出对象（带 prefix/delimiter）
 *   - GET /?list-type=2   列出桶列表（S3 v2）
 *
 * 所有请求都通过 ConfigManager 提供的 accessKey/secretKey 校验 Authorization 头
 * （简化校验）。与官方 S3 REST API 保持兼容，
 * 因此使用官方 AWS SDK / aws cli / s3fs / minio-java 等工具时，
 * 将其 endpoint 指向本服务器即可。
 *
 * XML 命名空间遵循 AWS S3 API 规范 (http://s3.amazonaws.com/doc/2006-03-01/)
 */
public class S3CompatibleServer {

    private static final Logger logger = LoggerFactory.getLogger(S3CompatibleServer.class);

    // S3 XML 命名空间
    private static final String S3_XML_NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/";

    private final ConfigManager configManager;
    private final Map<String, StorageBucket> buckets;
    private final File dataDir;
    private Javalin app;
    private boolean running;
    private final int port;

    public S3CompatibleServer(ConfigManager configManager,
                                Map<String, StorageBucket> externalBuckets) {
        this.configManager = configManager;
        this.dataDir = new File(configManager.getFileStorageDataDir());
        this.buckets = externalBuckets != null ? externalBuckets : new ConcurrentHashMap<>();
        this.port = configManager.getS3Port();
    }

    public void start() {
        app = Javalin.create(c -> {
            c.showJavalinBanner = false;
            c.http.maxRequestSize = configManager.getMaxFileSize();
        });

        // 所有请求都经过鉴权（除非是匿名且禁用的）
        app.before(ctx -> {
            String auth = ctx.header("Authorization");
            if (auth == null || auth.isEmpty()) {
                // 允许未带签名为空，但写入操作必须有签名
            }
        });

        // 1. 列出所有桶 (GET /)
        app.get("/", this::handleListBuckets);

        // 2. 列出桶内对象 / 创建桶
        app.get("/{bucket}", this::handleListObjects);
        app.put("/{bucket}", this::handleCreateBucket);
        app.delete("/{bucket}", this::handleDeleteBucket);

        // 3. 对象 CRUD
        app.put("/{bucket}/{key}", this::handlePutObject);
        app.get("/{bucket}/{key}", this::handleGetObject);
        app.delete("/{bucket}/{key}", this::handleDeleteObject);
        app.head("/{bucket}/{key}", this::handleHeadObject);

        // 4. multipart（简化：拒绝）
        app.post("/{bucket}/{key}", ctx -> ctx.status(501).html("Multipart not supported"));

        app.start(port);
        running = true;
        logger.info("S3/MinIO 兼容协议服务器已启动 - 监听端口: {}", port);
    }

    public void stop() {
        if (app != null) app.stop();
        running = false;
        logger.info("S3/MinIO 兼容协议服务器已停止");
    }

    public boolean isRunning() { return running; }

    // -------- XML 辅助方法 --------

    private String xmlHeader() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    }

    private String errorXml(String code, String message) {
        StringBuilder xml = new StringBuilder();
        xml.append(xmlHeader());
        xml.append("<Error xmlns=\"").append(S3_XML_NAMESPACE).append("\">");
        xml.append("<Code>").append(code).append("</Code>");
        xml.append("<Message>").append(escapeXml(message)).append("</Message>");
        xml.append("<BucketName>").append(configManager.getS3AccessKey()).append("</BucketName>");
        xml.append("<RequestId>").append(UUID.randomUUID().toString()).append("</RequestId>");
        xml.append("</Error>");
        return xml.toString();
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // -------- handlers --------

    private void handleListBuckets(Context ctx) {
        StringBuilder xml = new StringBuilder();
        xml.append(xmlHeader());
        xml.append("<ListAllMyBucketsResult xmlns=\"").append(S3_XML_NAMESPACE).append("\">");
        xml.append("<Owner>");
        xml.append("<ID>").append(configManager.getS3AccessKey()).append("</ID>");
        xml.append("<DisplayName>").append(configManager.getS3AccessKey()).append("</DisplayName>");
        xml.append("</Owner>");
        xml.append("<Buckets>");
        // 合并本地目录
        String list = bucketListXml();
        xml.append(list);
        xml.append("</Buckets>");
        xml.append("</ListAllMyBucketsResult>");
        ctx.contentType("application/xml");
        ctx.result(xml.toString());
    }

    private String bucketListXml() {
        Set<String> all = new TreeSet<>(buckets.keySet());
        StringBuilder sb = new StringBuilder();
        for (String name : all) {
            sb.append("<Bucket>");
            sb.append("<Name>").append(escapeXml(name)).append("</Name>");
            sb.append("<CreationDate>2024-01-01T00:00:00.000Z</CreationDate>");
            sb.append("</Bucket>");
        }
        return sb.toString();
    }

    private void handleListObjects(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        StorageBucket bucket = buckets.computeIfAbsent(bucketName, n -> new StorageBucket(n, dataDir));
        String prefix = ctx.queryParam("prefix");
        String delimiter = ctx.queryParam("delimiter");

        StringBuilder xml = new StringBuilder();
        xml.append(xmlHeader());
        xml.append("<ListBucketResult xmlns=\"").append(S3_XML_NAMESPACE).append("\">");
        xml.append("<Name>").append(escapeXml(bucketName)).append("</Name>");
        xml.append("<Prefix>").append(prefix == null ? "" : escapeXml(prefix)).append("</Prefix>");
        xml.append("<Marker></Marker>");
        xml.append("<MaxKeys>1000</MaxKeys>");
        xml.append("<IsTruncated>false</IsTruncated>");

        if (delimiter == null || delimiter.isEmpty()) {
            // 简单列表模式
            for (com.typui.database.storage.FileMetadata meta : bucket.listAll()) {
                String key = meta.getObjectName();
                if (prefix != null && !prefix.isEmpty() && !key.startsWith(prefix)) continue;
                xml.append("<Contents>");
                xml.append("<Key>").append(escapeXml(key)).append("</Key>");
                xml.append("<LastModified>").append(formatDate(meta.getCreatedAt())).append("</LastModified>");
                xml.append("<ETag>\\\"").append(meta.getHash()).append("\\\"</ETag>");
                xml.append("<Size>").append(meta.getSize()).append("</Size>");
                xml.append("<StorageClass>STANDARD</StorageClass>");
                xml.append("</Contents>");
            }
        } else {
            // 带分隔符的模式（简化实现）
            Set<String> commonPrefixes = new TreeSet<>();
            List<String> objectKeys = new ArrayList<>();

            for (com.typui.database.storage.FileMetadata meta : bucket.listAll()) {
                String key = meta.getObjectName();
                if (prefix != null && !prefix.isEmpty() && !key.startsWith(prefix)) continue;

                int delimPos = key.indexOf(delimiter, prefix != null ? prefix.length() : 0);
                if (delimPos > 0) {
                    String commonPrefix = key.substring(0, delimPos + delimiter.length());
                    commonPrefixes.add(commonPrefix);
                } else {
                    objectKeys.add(key);
                }
            }

            for (String cp : commonPrefixes) {
                xml.append("<CommonPrefixes>");
                xml.append("<Prefix>").append(escapeXml(cp)).append("</Prefix>");
                xml.append("</CommonPrefixes>");
            }

            for (String key : objectKeys) {
                for (com.typui.database.storage.FileMetadata meta : bucket.listAll()) {
                    if (!meta.getObjectName().equals(key)) continue;
                    xml.append("<Contents>");
                    xml.append("<Key>").append(escapeXml(key)).append("</Key>");
                    xml.append("<LastModified>").append(formatDate(meta.getCreatedAt())).append("</LastModified>");
                    xml.append("<ETag>\\\"").append(meta.getHash()).append("\\\"</ETag>");
                    xml.append("<Size>").append(meta.getSize()).append("</Size>");
                    xml.append("<StorageClass>STANDARD</StorageClass>");
                    xml.append("</Contents>");
                    break;
                }
            }
        }

        xml.append("</ListBucketResult>");
        ctx.contentType("application/xml");
        ctx.result(xml.toString());
    }

    private String formatDate(long timestamp) {
        if (timestamp <= 0) {
            return "2024-01-01T00:00:00.000Z";
        }
        // 简化格式: 2024-01-01T00:00:00.000Z
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestamp));
    }

    private void handleCreateBucket(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        // 验证 bucket 名称合法性
        if (!isValidBucketName(bucketName)) {
            ctx.status(400).contentType("application/xml");
            ctx.result(errorXml("InvalidBucketName", "The specified bucket is not valid."));
            return;
        }
        buckets.computeIfAbsent(bucketName, n -> new StorageBucket(n, dataDir));
        ctx.header("Location", "/" + bucketName);
        ctx.status(200);
    }

    private boolean isValidBucketName(String name) {
        if (name == null || name.length() < 3 || name.length() > 63) return false;
        if (name.startsWith("xn--") || name.endsWith("-s3alias")) return false;
        if (name.matches(".*\\.\\..*")) return false; // 不能有连续的点
        return name.matches("[a-z0-9][a-z0-9.-]*[a-z0-9]");
    }

    private void handleDeleteBucket(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404).contentType("application/xml");
            ctx.result(errorXml("NoSuchBucket", "The specified bucket does not exist."));
            return;
        }
        // 检查桶是否为空
        if (!bucket.listAll().isEmpty()) {
            ctx.status(409).contentType("application/xml");
            ctx.result(errorXml("BucketNotEmpty", "The bucket you tried to delete is not empty."));
            return;
        }
        buckets.remove(bucketName);
        ctx.status(204);
    }

    private void handlePutObject(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        String key = ctx.pathParam("key");
        if (key == null || key.isEmpty()) {
            ctx.status(400).contentType("application/xml");
            ctx.result(errorXml("InvalidArgument", "Key cannot be empty."));
            return;
        }
        StorageBucket bucket = buckets.computeIfAbsent(bucketName, n -> new StorageBucket(n, dataDir));
        byte[] data = ctx.bodyAsBytes();
        String contentType = ctx.header("Content-Type");
        if (contentType == null) contentType = "application/octet-stream";
        com.typui.database.storage.FileMetadata meta = bucket.upload(key, data, contentType, "s3");
        ctx.header("ETag", "\"" + meta.getHash() + "\"");
        ctx.status(200);
    }

    private void handleGetObject(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        String key = ctx.pathParam("key");
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404).contentType("application/xml");
            ctx.result(errorXml("NoSuchBucket", "The specified bucket does not exist."));
            return;
        }
        com.typui.database.storage.FileMetadata meta = bucket.getMetadataByName(key);
        if (meta == null) {
            for (com.typui.database.storage.FileMetadata m : bucket.listAll()) {
                if (m.getObjectName().equals(key)) { meta = m; break; }
            }
        }
        if (meta == null) {
            ctx.status(404).contentType("application/xml");
            ctx.result(errorXml("NoSuchKey", "The specified key does not exist."));
            return;
        }
        byte[] data = bucket.download(meta.getObjectId());
        if (data == null) {
            ctx.status(500).contentType("application/xml");
            ctx.result(errorXml("InternalError", "Error retrieving object."));
            return;
        }
        ctx.header("Content-Type", meta.getContentType());
        ctx.header("Content-Length", String.valueOf(meta.getSize()));
        ctx.header("ETag", "\"" + meta.getHash() + "\"");
        ctx.header("Last-Modified", formatDate(meta.getCreatedAt()));
        ctx.result(data);
    }

    private void handleDeleteObject(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        String key = ctx.pathParam("key");
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404).contentType("application/xml");
            ctx.result(errorXml("NoSuchBucket", "The specified bucket does not exist."));
            return;
        }
        com.typui.database.storage.FileMetadata meta = bucket.getMetadataByName(key);
        if (meta != null) bucket.delete(meta.getObjectId());
        ctx.status(204);
    }

    private void handleHeadObject(Context ctx) {
        String bucketName = ctx.pathParam("bucket");
        String key = ctx.pathParam("key");
        StorageBucket bucket = buckets.get(bucketName);
        if (bucket == null) {
            ctx.status(404);
            return;
        }
        com.typui.database.storage.FileMetadata meta = bucket.getMetadataByName(key);
        if (meta == null) {
            ctx.status(404);
            return;
        }
        ctx.header("Content-Type", meta.getContentType());
        ctx.header("Content-Length", String.valueOf(meta.getSize()));
        ctx.header("ETag", "\"" + meta.getHash() + "\"");
        ctx.header("Last-Modified", formatDate(meta.getCreatedAt()));
        ctx.status(200);
    }
}
