package com.typui.database.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typui.database.client.HttpUtil;

import java.util.*;
import java.util.function.Consumer;

/**
 * MongoDB兼容集合HTTP适配器
 * 通过HTTP API访问文档数据库
 */
public class MongoCollectionAdapterHttp extends MongoCollectionAdapter {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String host;
    private final int port;
    private final String databaseName;
    private final String collectionName;
    
    public MongoCollectionAdapterHttp(String host, int port, String databaseName, String collectionName) {
        super(null, collectionName);
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }
    
    private String getBaseUrl() {
        return String.format("http://%s:%d/%s/%s", host, port, databaseName, collectionName);
    }
    
    @Override
    public void insertOne(Map<String, Object> document) {
        try {
            String url = getBaseUrl();
            HttpUtil.post(url, document);
        } catch (Exception e) {
            throw new RuntimeException("插入文档失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void insertMany(List<Map<String, Object>> documents) {
        try {
            String url = getBaseUrl() + "/bulk";
            Map<String, Object> body = new HashMap<>();
            body.put("documents", documents);
            HttpUtil.post(url, body);
        } catch (Exception e) {
            throw new RuntimeException("批量插入失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Map<String, Object> findFirst(Map<String, Object> filter) {
        try {
            if (filter == null || filter.isEmpty()) {
                String url = getBaseUrl();
                Map<String, Object> result = HttpUtil.get(url);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
                return documents != null && !documents.isEmpty() ? documents.get(0) : null;
            } else {
                String url = getBaseUrl() + "/query";
                Map<String, Object> body = new HashMap<>();
                body.put("query", filter);
                Map<String, Object> result = HttpUtil.post(url, body);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
                return documents != null && !documents.isEmpty() ? documents.get(0) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("查询失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public List<Map<String, Object>> findAll() {
        try {
            String url = getBaseUrl();
            Map<String, Object> result = HttpUtil.get(url);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
            return documents != null ? documents : new ArrayList<>();
        } catch (Exception e) {
            throw new RuntimeException("查询失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public FindIterable find(Map<String, Object> filter) {
        try {
            String url = getBaseUrl() + "/query";
            Map<String, Object> body = new HashMap<>();
            body.put("query", filter);
            Map<String, Object> result = HttpUtil.post(url, body);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
            return new FindIterable(documents != null ? documents : new ArrayList<>());
        } catch (Exception e) {
            throw new RuntimeException("查询失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public UpdateResult updateOne(Map<String, Object> filter, Map<String, Object> update) {
        try {
            Map<String, Object> doc = findFirst(filter);
            if (doc == null) {
                System.out.println("[DEBUG] updateOne: 文档未找到, filter=" + filter);
                return new UpdateResult(0);
            }
            
            String id = (String) doc.get("_id");
            System.out.println("[DEBUG] updateOne: 找到文档, _id=" + id + ", filter=" + filter);
            
            String url = getBaseUrl() + "/" + id;
            
            // 提取 $set 操作符中的内容
            Map<String, Object> updateBody = update;
            if (update.containsKey("$set")) {
                updateBody = (Map<String, Object>) update.get("$set");
            }
            
            System.out.println("[DEBUG] updateOne: 发送更新请求, url=" + url + ", body=" + updateBody);
            
            HttpUtil.put(url, updateBody);
            return new UpdateResult(1);
        } catch (Exception e) {
            System.out.println("[DEBUG] updateOne 异常: " + e.getMessage());
            throw new RuntimeException("更新失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public UpdateResult updateMany(Map<String, Object> filter, Map<String, Object> update) {
        try {
            String url = getBaseUrl() + "/update";
            Map<String, Object> body = new HashMap<>();
            body.put("query", filter);
            body.put("update", update);
            Map<String, Object> result = HttpUtil.post(url, body);
            long count = ((Number) result.get("modifiedCount")).longValue();
            return new UpdateResult(count);
        } catch (Exception e) {
            throw new RuntimeException("批量更新失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public DeleteResult deleteOne(Map<String, Object> filter) {
        try {
            Map<String, Object> doc = findFirst(filter);
            if (doc == null) {
                return new DeleteResult(0);
            }
            
            String id = (String) doc.get("_id");
            String url = getBaseUrl() + "/" + id;
            HttpUtil.delete(url);
            return new DeleteResult(1);
        } catch (Exception e) {
            throw new RuntimeException("删除失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public DeleteResult deleteMany(Map<String, Object> filter) {
        try {
            String url = getBaseUrl() + "/delete";
            Map<String, Object> body = new HashMap<>();
            body.put("query", filter);
            Map<String, Object> result = HttpUtil.post(url, body);
            long count = ((Number) result.get("deletedCount")).longValue();
            return new DeleteResult(count);
        } catch (Exception e) {
            throw new RuntimeException("批量删除失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public long countDocuments(Map<String, Object> filter) {
        try {
            String url = getBaseUrl() + "/count";
            if (filter != null && !filter.isEmpty()) {
                Map<String, Object> body = new HashMap<>();
                body.put("query", filter);
                Map<String, Object> result = HttpUtil.post(url, body);
                return ((Number) result.get("count")).longValue();
            } else {
                Map<String, Object> result = HttpUtil.get(url);
                return ((Number) result.get("count")).longValue();
            }
        } catch (Exception e) {
            throw new RuntimeException("统计失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public long countDocuments() {
        return countDocuments(null);
    }
}
