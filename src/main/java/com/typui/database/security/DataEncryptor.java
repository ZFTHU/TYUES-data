package com.typui.database.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 数据加密工具类
 * 使用 AES-256-GCM 算法进行数据加密
 */
public class DataEncryptor {
    
    private static final Logger logger = LoggerFactory.getLogger(DataEncryptor.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    
    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom;
    
    public DataEncryptor(String secretKey) {
        byte[] keyBytes = deriveKey(secretKey);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.secureRandom = new SecureRandom();
    }
    
    /**
     * 从密钥字符串派生256位密钥
     */
    private byte[] deriveKey(String secretKey) {
        byte[] keyBytes = new byte[32];
        byte[] secretBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) (secretBytes[i % secretBytes.length] ^ (i * 7));
        }
        
        return keyBytes;
    }
    
    /**
     * 加密数据
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
            
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            
            byte[] cipherTextWithIv = new byte[GCM_IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, cipherTextWithIv, 0, GCM_IV_LENGTH);
            System.arraycopy(cipherText, 0, cipherTextWithIv, GCM_IV_LENGTH, cipherText.length);
            
            return Base64.getEncoder().encodeToString(cipherTextWithIv);
        } catch (Exception e) {
            logger.error("加密失败", e);
            throw new RuntimeException("加密失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解密数据
     */
    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        
        try {
            byte[] cipherTextWithIv = Base64.getDecoder().decode(encryptedText);
            
            if (cipherTextWithIv.length < GCM_IV_LENGTH) {
                return encryptedText;
            }
            
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(cipherTextWithIv, 0, iv, 0, GCM_IV_LENGTH);
            
            byte[] cipherText = new byte[cipherTextWithIv.length - GCM_IV_LENGTH];
            System.arraycopy(cipherTextWithIv, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);
            
            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("解密失败，返回原文: {}", e.getMessage());
            return encryptedText;
        }
    }
    
    /**
     * 检查是否是加密数据
     */
    public boolean isEncrypted(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        try {
            byte[] decoded = Base64.getDecoder().decode(text);
            return decoded.length >= GCM_IV_LENGTH;
        } catch (Exception e) {
            return false;
        }
    }
}
