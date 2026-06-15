package com.typui.database.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DocumentCollection {
    
    private static final Logger logger = LoggerFactory.getLogger(DocumentCollection.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String name;
    private final File collectionDir;
    private final Map<String, Map<String, Object>> documents;
    private final Map<String, Map<String, Object>> indexes;
    private final AtomicLong idCounter;
    private final Map<String, ReentrantReadWriteLock> fileLocks;
    private final com.typui.database.security.DataEncryptor encryptor;
    
    public DocumentCollection(String name, File dataDir) {
        this.name = name;
        this.collectionDir = new File(dataDir, name);
        this.documents = new ConcurrentHashMap<>();
        this.indexes = new ConcurrentHashMap<>();
        this.idCounter = new AtomicLong(0);
        this.fileLocks = new ConcurrentHashMap<>();
        // 使用 collection 目录绝对路径作为加密 key 的一部分（不同目录不同密钥派生）
        String baseKey = System.getProperty("typui.doc.encryption.key",
                "typui-doc-default-key-2024");
        this.encryptor = new com.typui.database.security.DataEncryptor(
                baseKey + "|" + dataDir.getAbsolutePath() + "|" + name);
        
        initializeCollection();
    }
    
    private void initializeCollection() {
        if (!collectionDir.exists()) {
            collectionDir.mkdirs();
            logger.info("集合目录创建成功: {}", collectionDir.getAbsolutePath());
        }
        
        loadDocuments();
        buildIndexes();
    }
    
    private void loadDocuments() {
        File[] files = collectionDir.listFiles((dir, name) ->
                name.endsWith(".json") || name.endsWith(".json.enc"));
        if (files != null) {
            for (File file : files) {
                try {
                    Map<String, Object> doc;
                    if (file.getName().endsWith(".json.enc")) {
                        // 已加密的文档（完全加密，记事本无法查看）
                        String raw = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8);
                        String plain = encryptor.decrypt(raw.trim());
                        doc = objectMapper.readValue(plain, Map.class);
                    } else {
                        // 未加密的旧文档（兼容读取）
                        doc = objectMapper.readValue(file, Map.class);
                    }
                    String id = (String) doc.get("_id");
                    if (id != null) {
                        documents.put(id, doc);
                        updateIdCounter(id);
                    }
                } catch (IOException e) {
                    logger.error("加载文档失败: {}", file.getName(), e);
                }
            }
        }
        logger.info("集合 {} 加载完成，共 {} 个文档", name, documents.size());
    }
    
    private void updateIdCounter(String id) {
        try {
            if (id.startsWith(name + "_")) {
                long num = Long.parseLong(id.substring(name.length() + 1));
                if (num > idCounter.get()) {
                    idCounter.set(num);
                }
            }
        } catch (NumberFormatException e) {
        }
    }
    
    private void buildIndexes() {
        indexes.put("_id", new ConcurrentHashMap<>());
        for (Map.Entry<String, Map<String, Object>> entry : documents.entrySet()) {
            indexes.get("_id").put(entry.getKey(), entry.getValue());
        }
    }
    
    private String generateId() {
        return name + "_" + idCounter.incrementAndGet();
    }
    
    public Map<String, Object> insert(Map<String, Object> document) {
        String id = (String) document.get("_id");
        if (id == null || id.isEmpty()) {
            id = generateId();
        }
        document.put("_id", id);
        document.put("_createdAt", System.currentTimeMillis());
        document.put("_updatedAt", System.currentTimeMillis());
        
        documents.put(id, document);
        indexes.get("_id").put(id, document);
        
        saveDocumentAsync(id, document);
        logger.debug("文档插入成功: {}", id);
        
        return document;
    }
    
    public List<Map<String, Object>> insertMany(List<Map<String, Object>> docs) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            result.add(insert(doc));
        }
        return result;
    }
    
    public Map<String, Object> findById(String id) {
        return documents.get(id);
    }
    
    public List<Map<String, Object>> findAll() {
        return new ArrayList<>(documents.values());
    }
    
    public List<Map<String, Object>> find(Map<String, Object> query) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> doc : documents.values()) {
            if (matchesQuery(doc, query)) {
                result.add(doc);
            }
        }
        return result;
    }
    
    public Map<String, Object> findOne(Map<String, Object> query) {
        for (Map<String, Object> doc : documents.values()) {
            if (matchesQuery(doc, query)) {
                return doc;
            }
        }
        return null;
    }
    
    @SuppressWarnings("unchecked")
    private boolean matchesQuery(Map<String, Object> doc, Map<String, Object> query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            String key = entry.getKey();
            Object queryValue = entry.getValue();
            Object docValue = getNestedValue(doc, key);
            
            if (queryValue instanceof Map) {
                Map<String, Object> operators = (Map<String, Object>) queryValue;
                for (Map.Entry<String, Object> op : operators.entrySet()) {
                    if (!evaluateOperator(op.getKey(), docValue, op.getValue())) {
                        return false;
                    }
                }
            } else {
                if (!Objects.equals(docValue, queryValue)) {
                    return false;
                }
            }
        }
        return true;
    }
    
    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> doc, String path) {
        String[] parts = path.split("\\.");
        Object current = doc;
        
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        
        return current;
    }
    
    @SuppressWarnings("unchecked")
    private boolean evaluateOperator(String operator, Object docValue, Object queryValue) {
        switch (operator) {
            case "$eq":
                return Objects.equals(docValue, queryValue);
            case "$ne":
                return !Objects.equals(docValue, queryValue);
            case "$gt":
                if (docValue instanceof Number && queryValue instanceof Number) {
                    return ((Number) docValue).doubleValue() > ((Number) queryValue).doubleValue();
                }
                return false;
            case "$gte":
                if (docValue instanceof Number && queryValue instanceof Number) {
                    return ((Number) docValue).doubleValue() >= ((Number) queryValue).doubleValue();
                }
                return false;
            case "$lt":
                if (docValue instanceof Number && queryValue instanceof Number) {
                    return ((Number) docValue).doubleValue() < ((Number) queryValue).doubleValue();
                }
                return false;
            case "$lte":
                if (docValue instanceof Number && queryValue instanceof Number) {
                    return ((Number) docValue).doubleValue() <= ((Number) queryValue).doubleValue();
                }
                return false;
            case "$in":
                if (queryValue instanceof List) {
                    return ((List<?>) queryValue).contains(docValue);
                }
                return false;
            case "$nin":
                if (queryValue instanceof List) {
                    return !((List<?>) queryValue).contains(docValue);
                }
                return false;
            case "$exists":
                boolean exists = docValue != null;
                return queryValue instanceof Boolean && ((Boolean) queryValue) == exists;
            case "$regex":
                if (docValue instanceof String && queryValue instanceof String) {
                    return ((String) docValue).matches((String) queryValue);
                }
                return false;
            case "$contains":
                if (docValue instanceof String && queryValue instanceof String) {
                    return ((String) docValue).contains((String) queryValue);
                }
                if (docValue instanceof List) {
                    return ((List<?>) docValue).contains(queryValue);
                }
                return false;
            default:
                return false;
        }
    }
    
    public Map<String, Object> updateById(String id, Map<String, Object> update) {
        ReentrantReadWriteLock lock = fileLocks.computeIfAbsent(id, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        try {
            Map<String, Object> doc = documents.get(id);
            if (doc == null) {
                return null;
            }

            applyUpdate(doc, update);
            doc.put("_updatedAt", System.currentTimeMillis());

            saveDocumentUnderLock(id, doc);
            logger.debug("文档更新成功: {}", id);

            return doc;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public long updateMany(Map<String, Object> query, Map<String, Object> update) {
        long count = 0;
        for (Map<String, Object> doc : find(query)) {
            String id = (String) doc.get("_id");
            updateById(id, update);
            count++;
        }
        return count;
    }
    
    @SuppressWarnings("unchecked")
    private void applyUpdate(Map<String, Object> doc, Map<String, Object> update) {
        for (Map.Entry<String, Object> entry : update.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (key.startsWith("$")) {
                switch (key) {
                    case "$set":
                        if (value instanceof Map) {
                            doc.putAll((Map<String, Object>) value);
                        }
                        break;
                    case "$unset":
                        if (value instanceof Map) {
                            for (String field : ((Map<String, Object>) value).keySet()) {
                                doc.remove(field);
                            }
                        }
                        break;
                    case "$inc":
                        if (value instanceof Map) {
                            for (Map.Entry<String, Object> inc : ((Map<String, Object>) value).entrySet()) {
                                Object current = doc.get(inc.getKey());
                                if (current instanceof Number && inc.getValue() instanceof Number) {
                                    double newVal = ((Number) current).doubleValue() + ((Number) inc.getValue()).doubleValue();
                                    doc.put(inc.getKey(), newVal);
                                }
                            }
                        }
                        break;
                    case "$push":
                        if (value instanceof Map) {
                            for (Map.Entry<String, Object> push : ((Map<String, Object>) value).entrySet()) {
                                Object current = doc.get(push.getKey());
                                if (current instanceof List) {
                                    ((List<Object>) current).add(push.getValue());
                                } else {
                                    List<Object> list = new ArrayList<>();
                                    list.add(push.getValue());
                                    doc.put(push.getKey(), list);
                                }
                            }
                        }
                        break;
                    case "$pull":
                        if (value instanceof Map) {
                            for (Map.Entry<String, Object> pull : ((Map<String, Object>) value).entrySet()) {
                                Object current = doc.get(pull.getKey());
                                if (current instanceof List) {
                                    ((List<Object>) current).remove(pull.getValue());
                                }
                            }
                        }
                        break;
                }
            } else {
                doc.put(key, value);
            }
        }
    }
    
    public boolean deleteById(String id) {
        Map<String, Object> removed = documents.remove(id);
        if (removed != null) {
            indexes.get("_id").remove(id);
            deleteDocumentFile(id);
            logger.debug("文档删除成功: {}", id);
            return true;
        }
        return false;
    }
    
    public long deleteMany(Map<String, Object> query) {
        long count = 0;
        List<Map<String, Object>> toDelete = find(query);
        for (Map<String, Object> doc : toDelete) {
            String id = (String) doc.get("_id");
            if (deleteById(id)) {
                count++;
            }
        }
        return count;
    }
    
    public long count() {
        return documents.size();
    }
    
    public long count(Map<String, Object> query) {
        return find(query).size();
    }
    
    public void createIndex(String field) {
        if (!indexes.containsKey(field)) {
            Map<String, Object> index = new ConcurrentHashMap<>();
            for (Map<String, Object> doc : documents.values()) {
                Object value = doc.get(field);
                if (value != null) {
                    index.put(String.valueOf(value), doc);
                }
            }
            indexes.put(field, index);
            logger.info("索引创建成功: {} - {}", name, field);
        }
    }
    
    private void writeDocumentFile(String id, Map<String, Object> document) {
        // 写入 .json.enc（完全加密），同时删除同名的旧 .json 防止残留
        File plainFile = new File(collectionDir, id + ".json");
        File encFile = new File(collectionDir, id + ".json.enc");
        try {
            String plain = objectMapper.writeValueAsString(document);
            String encrypted = encryptor.encrypt(plain);
            java.nio.file.Files.write(encFile.toPath(),
                    encrypted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (plainFile.exists()) {
                plainFile.delete();
            }
        } catch (IOException e) {
            logger.error("保存文档失败: {}", id, e);
        }
    }

    private void saveDocumentUnderLock(String id, Map<String, Object> document) {
        // Caller is expected to hold fileLocks[id].writeLock()
        writeDocumentFile(id, document);
    }

    private void saveDocumentAsync(String id, Map<String, Object> document) {
        ReentrantReadWriteLock lock = fileLocks.computeIfAbsent(id, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        try {
            writeDocumentFile(id, document);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private void deleteDocumentFile(String id) {
        ReentrantReadWriteLock lock = fileLocks.get(id);
        File encFile = new File(collectionDir, id + ".json.enc");
        File plainFile = new File(collectionDir, id + ".json");

        Runnable doDelete = () -> {
            if (encFile.exists()) encFile.delete();
            if (plainFile.exists()) plainFile.delete();
        };

        if (lock != null) {
            lock.writeLock().lock();
            try {
                doDelete.run();
            } finally {
                lock.writeLock().unlock();
                fileLocks.remove(id);
            }
        } else {
            doDelete.run();
        }
    }
    
    /**
     * 原子化地对指定计数器文档的值进行 +1。
     * 若文档不存在，则创建一个初始值为 100001 的新计数器文档。
     * 该方法通过在文档 id 上使用写锁保证同一计数器的并发请求串行执行，
     * 避免竞态条件下的增量丢失。
     */
    public long atomicIncrement(String counterId) {
        ReentrantReadWriteLock lock = fileLocks.computeIfAbsent(counterId, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        try {
            Map<String, Object> doc = documents.get(counterId);
            long newValue;
            if (doc == null) {
                doc = new HashMap<>();
                doc.put("_id", counterId);
                newValue = 100001L;
                doc.put("value", newValue);
                doc.put("_createdAt", System.currentTimeMillis());
                doc.put("_updatedAt", System.currentTimeMillis());
                documents.put(counterId, doc);
                indexes.get("_id").put(counterId, doc);
                writeDocumentFile(counterId, doc);
            } else {
                Object current = doc.get("value");
                long currentVal = (current instanceof Number) ? ((Number) current).longValue() : 0L;
                newValue = currentVal + 1;
                doc.put("value", newValue);
                doc.put("_updatedAt", System.currentTimeMillis());
                writeDocumentFile(counterId, doc);
            }
            logger.debug("计数器原子递增: {} -> {}", counterId, newValue);
            return newValue;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String getName() {
        return name;
    }
    
    public void clear() {
        documents.clear();
        for (Map<String, Object> index : indexes.values()) {
            index.clear();
        }
        
        File[] jsonFiles = collectionDir.listFiles((dir, name) ->
                name.endsWith(".json") || name.endsWith(".json.enc"));
        if (jsonFiles != null) {
            for (File file : jsonFiles) {
                file.delete();
            }
        }
        fileLocks.clear();
        logger.info("集合已清空: {}", name);
    }
}
