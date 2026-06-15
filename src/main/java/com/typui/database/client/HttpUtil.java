package com.typui.database.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP工具类
 */
public class HttpUtil {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 120000; // 2分钟，用于大文件上传
    
    /**
     * GET请求
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> get(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Accept", "application/json");
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                String response = readResponse(conn.getInputStream());
                return objectMapper.readValue(response, Map.class);
            } else {
                String error = readResponse(conn.getErrorStream());
                throw new RuntimeException("HTTP " + responseCode + ": " + error);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * POST请求
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> post(String url, Map<String, Object> body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            
            String jsonBody = objectMapper.writeValueAsString(body);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                String response = readResponse(conn.getInputStream());
                return objectMapper.readValue(response, Map.class);
            } else {
                String error = readResponse(conn.getErrorStream());
                throw new RuntimeException("HTTP " + responseCode + ": " + error);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * PUT请求
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> put(String url, Map<String, Object> body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            
            String jsonBody = objectMapper.writeValueAsString(body);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                String response = readResponse(conn.getInputStream());
                return objectMapper.readValue(response, Map.class);
            } else {
                String error = readResponse(conn.getErrorStream());
                throw new RuntimeException("HTTP " + responseCode + ": " + error);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * DELETE请求
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> delete(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Accept", "application/json");
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                String response = readResponse(conn.getInputStream());
                return objectMapper.readValue(response, Map.class);
            } else {
                String error = readResponse(conn.getErrorStream());
                throw new RuntimeException("HTTP " + responseCode + ": " + error);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * 上传文件
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> uploadFile(String url, String fileName, byte[] data, String contentType) throws Exception {
        HttpURLConnection conn = null;
        try {
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Accept", "application/json");
            
            try (OutputStream os = conn.getOutputStream();
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true)) {
                
                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                      .append(fileName).append("\"\r\n");
                writer.append("Content-Type: ").append(contentType != null ? contentType : "application/octet-stream")
                      .append("\r\n\r\n");
                writer.flush();
                
                os.write(data);
                os.flush();
                
                writer.append("\r\n--").append(boundary).append("--\r\n");
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                String response = readResponse(conn.getInputStream());
                return objectMapper.readValue(response, Map.class);
            } else {
                String error = readResponse(conn.getErrorStream());
                throw new RuntimeException("HTTP " + responseCode + ": " + error);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * 下载文件
     */
    public static byte[] download(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    return baos.toByteArray();
                }
            } else {
                String error = readResponse(conn.getErrorStream());
                throw new RuntimeException("HTTP " + responseCode + ": " + error);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * 读取响应
     */
    private static String readResponse(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
