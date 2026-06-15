package com.typui.database.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 预签名URL生成器
 * 支持生成临时下载/上传链接
 */
public class PresignedUrlGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(PresignedUrlGenerator.class);
    
    private final String host;
    private final int port;
    private final String secretKey;
    
    public PresignedUrlGenerator(String host, int port, String secretKey) {
        this.host = host;
        this.port = port;
        this.secretKey = secretKey;
    }
    
    /**
     * 生成下载预签名URL
     */
    public PresignedUrl generateDownloadUrl(String bucket, String objectId, 
                                            long expireSeconds, String ipRestriction) {
        long expireTime = System.currentTimeMillis() + expireSeconds * 1000;
        
        String signature = generateSignature("GET", bucket, objectId, expireTime, ipRestriction);
        
        PresignedUrl url = new PresignedUrl();
        url.setUrl(buildUrl("download", bucket, objectId, signature, expireTime, ipRestriction));
        url.setMethod("GET");
        url.setBucket(bucket);
        url.setObjectId(objectId);
        url.setExpireTime(expireTime);
        url.setSignature(signature);
        url.setIpRestriction(ipRestriction);
        
        logger.debug("生成下载预签名URL: {}/{}", bucket, objectId);
        
        return url;
    }
    
    /**
     * 生成上传预签名URL
     */
    public PresignedUrl generateUploadUrl(String bucket, String objectName, 
                                          long expireSeconds, String ipRestriction,
                                          String contentType, long maxFileSize) {
        long expireTime = System.currentTimeMillis() + expireSeconds * 1000;
        
        String signature = generateSignature("POST", bucket, objectName, expireTime, ipRestriction);
        
        PresignedUrl url = new PresignedUrl();
        url.setUrl(buildUrl("upload", bucket, objectName, signature, expireTime, ipRestriction));
        url.setMethod("POST");
        url.setBucket(bucket);
        url.setObjectId(objectName);
        url.setExpireTime(expireTime);
        url.setSignature(signature);
        url.setIpRestriction(ipRestriction);
        url.setContentType(contentType);
        url.setMaxFileSize(maxFileSize);
        
        logger.debug("生成上传预签名URL: {}/{}", bucket, objectName);
        
        return url;
    }
    
    /**
     * 验证预签名URL
     */
    public ValidationResult validateUrl(String method, String bucket, String objectId,
                                        String signature, long expireTime, String ipRestriction,
                                        String clientIp) {
        if (System.currentTimeMillis() > expireTime) {
            return ValidationResult.failure("URL已过期");
        }
        
        if (ipRestriction != null && !ipRestriction.isEmpty()) {
            if (!ipRestriction.equals(clientIp)) {
                return ValidationResult.failure("IP地址不匹配");
            }
        }
        
        String expectedSignature = generateSignature(method, bucket, objectId, expireTime, ipRestriction);
        
        if (!expectedSignature.equals(signature)) {
            return ValidationResult.failure("签名无效");
        }
        
        return ValidationResult.success();
    }
    
    /**
     * 生成签名
     */
    private String generateSignature(String method, String bucket, String objectId,
                                     long expireTime, String ipRestriction) {
        try {
            String data = method + "\n" + bucket + "\n" + objectId + "\n" + 
                         expireTime + "\n" + (ipRestriction != null ? ipRestriction : "");
            
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            
            return sb.toString();
        } catch (Exception e) {
            logger.error("生成签名失败", e);
            return "";
        }
    }
    
    /**
     * 构建URL
     */
    private String buildUrl(String action, String bucket, String objectId,
                           String signature, long expireTime, String ipRestriction) {
        StringBuilder url = new StringBuilder();
        url.append("http://").append(host).append(":").append(port);
        url.append("/presigned/").append(action);
        url.append("/").append(bucket);
        url.append("/").append(objectId);
        url.append("?signature=").append(signature);
        url.append("&expire=").append(expireTime);
        
        if (ipRestriction != null && !ipRestriction.isEmpty()) {
            url.append("&ip=").append(ipRestriction);
        }
        
        return url.toString();
    }
    
    /**
     * 预签名URL类
     */
    public static class PresignedUrl {
        private String url;
        private String method;
        private String bucket;
        private String objectId;
        private long expireTime;
        private String signature;
        private String ipRestriction;
        private String contentType;
        private long maxFileSize;
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getObjectId() { return objectId; }
        public void setObjectId(String objectId) { this.objectId = objectId; }
        public long getExpireTime() { return expireTime; }
        public void setExpireTime(long expireTime) { this.expireTime = expireTime; }
        public String getSignature() { return signature; }
        public void setSignature(String signature) { this.signature = signature; }
        public String getIpRestriction() { return ipRestriction; }
        public void setIpRestriction(String ipRestriction) { this.ipRestriction = ipRestriction; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public long getMaxFileSize() { return maxFileSize; }
        public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
    }
    
    /**
     * 验证结果类
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String error;
        
        private ValidationResult(boolean valid, String error) {
            this.valid = valid;
            this.error = error;
        }
        
        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }
        
        public static ValidationResult failure(String error) {
            return new ValidationResult(false, error);
        }
        
        public boolean isValid() { return valid; }
        public String getError() { return error; }
    }
}
