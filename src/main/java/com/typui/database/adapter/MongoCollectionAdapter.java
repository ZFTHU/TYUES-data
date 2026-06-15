package com.typui.database.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typui.database.document.DocumentCollection;
import com.typui.database.document.DocumentDatabase;

import java.util.*;
import java.util.function.Consumer;

/**
 * MongoDB兼容集合适配器
 * 模拟MongoDB的MongoCollection接口
 */
public class MongoCollectionAdapter {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final DocumentCollection collection;
    private final String collectionName;
    
    public MongoCollectionAdapter(DocumentDatabase database, String collectionName) {
        this.collection = database != null ? database.getCollection(collectionName) : null;
        this.collectionName = collectionName;
    }
    
    /**
     * 插入单个文档
     */
    public void insertOne(Map<String, Object> document) {
        collection.insert(document);
    }
    
    /**
     * 插入多个文档
     */
    public void insertMany(List<Map<String, Object>> documents) {
        collection.insertMany(documents);
    }
    
    /**
     * 查找单个文档
     */
    public Map<String, Object> findFirst(Map<String, Object> filter) {
        return collection.findOne(filter);
    }
    
    /**
     * 查找所有文档
     */
    public List<Map<String, Object>> findAll() {
        return collection.findAll();
    }
    
    /**
     * 根据条件查找
     */
    public FindIterable find(Map<String, Object> filter) {
        return new FindIterable(collection.find(filter));
    }
    
    /**
     * 更新单个文档
     */
    public UpdateResult updateOne(Map<String, Object> filter, Map<String, Object> update) {
        Map<String, Object> doc = collection.findOne(filter);
        if (doc != null) {
            String id = (String) doc.get("_id");
            collection.updateById(id, update);
            return new UpdateResult(1);
        }
        return new UpdateResult(0);
    }
    
    /**
     * 更新多个文档
     */
    public UpdateResult updateMany(Map<String, Object> filter, Map<String, Object> update) {
        long count = collection.updateMany(filter, update);
        return new UpdateResult(count);
    }
    
    /**
     * 删除单个文档
     */
    public DeleteResult deleteOne(Map<String, Object> filter) {
        Map<String, Object> doc = collection.findOne(filter);
        if (doc != null) {
            String id = (String) doc.get("_id");
            collection.deleteById(id);
            return new DeleteResult(1);
        }
        return new DeleteResult(0);
    }
    
    /**
     * 删除多个文档
     */
    public DeleteResult deleteMany(Map<String, Object> filter) {
        long count = collection.deleteMany(filter);
        return new DeleteResult(count);
    }
    
    /**
     * 统计文档数量
     */
    public long countDocuments(Map<String, Object> filter) {
        return collection.count(filter);
    }
    
    /**
     * 统计所有文档数量
     */
    public long countDocuments() {
        return collection.count();
    }
    
    /**
     * 查找迭代器类
     */
    public static class FindIterable implements Iterable<Map<String, Object>> {
        
        private final List<Map<String, Object>> documents;
        private int skip = 0;
        private int limit = Integer.MAX_VALUE;
        private Comparator<Map<String, Object>> sorter;
        
        public FindIterable(List<Map<String, Object>> documents) {
            this.documents = new ArrayList<>(documents);
        }
        
        /**
         * 排序
         */
        public FindIterable sort(Map<String, Object> sortSpec) {
            if (sortSpec != null && !sortSpec.isEmpty()) {
                Map.Entry<String, Object> entry = sortSpec.entrySet().iterator().next();
                String field = entry.getKey();
                int direction = ((Number) entry.getValue()).intValue();
                
                sorter = (a, b) -> {
                    Object valA = a.get(field);
                    Object valB = b.get(field);
                    
                    if (valA == null && valB == null) return 0;
                    if (valA == null) return direction;
                    if (valB == null) return -direction;
                    
                    if (valA instanceof Number && valB instanceof Number) {
                        double diff = ((Number) valA).doubleValue() - ((Number) valB).doubleValue();
                        return (int) (diff * direction);
                    }
                    
                    if (valA instanceof Comparable && valB instanceof Comparable) {
                        @SuppressWarnings("unchecked")
                        int cmp = ((Comparable<Object>) valA).compareTo(valB);
                        return cmp * direction;
                    }
                    
                    return 0;
                };
            }
            return this;
        }
        
        /**
         * 跳过指定数量
         */
        public FindIterable skip(int skip) {
            this.skip = skip;
            return this;
        }
        
        /**
         * 限制返回数量
         */
        public FindIterable limit(int limit) {
            this.limit = limit;
            return this;
        }
        
        /**
         * 获取第一个文档
         */
        public Map<String, Object> first() {
            if (documents.isEmpty()) {
                return null;
            }
            
            List<Map<String, Object>> sorted = new ArrayList<>(documents);
            if (sorter != null) {
                sorted.sort(sorter);
            }
            
            if (skip < sorted.size()) {
                return sorted.get(skip);
            }
            return null;
        }
        
        /**
         * 遍历所有文档
         */
        public void each(Consumer<Map<String, Object>> consumer) {
            List<Map<String, Object>> sorted = new ArrayList<>(documents);
            if (sorter != null) {
                sorted.sort(sorter);
            }
            
            int end = Math.min(skip + limit, sorted.size());
            for (int i = skip; i < end; i++) {
                consumer.accept(sorted.get(i));
            }
        }
        
        /**
         * 转换为列表
         */
        public List<Map<String, Object>> into(List<Map<String, Object>> target) {
            List<Map<String, Object>> sorted = new ArrayList<>(documents);
            if (sorter != null) {
                sorted.sort(sorter);
            }
            
            int end = Math.min(skip + limit, sorted.size());
            for (int i = skip; i < end; i++) {
                target.add(sorted.get(i));
            }
            return target;
        }
        
        @Override
        public Iterator<Map<String, Object>> iterator() {
            List<Map<String, Object>> sorted = new ArrayList<>(documents);
            if (sorter != null) {
                sorted.sort(sorter);
            }
            
            int end = Math.min(skip + limit, sorted.size());
            return sorted.subList(skip, end).iterator();
        }
    }
    
    /**
     * 更新结果类
     */
    public static class UpdateResult {
        private final long modifiedCount;
        
        public UpdateResult(long modifiedCount) {
            this.modifiedCount = modifiedCount;
        }
        
        public long getModifiedCount() {
            return modifiedCount;
        }
    }
    
    /**
     * 删除结果类
     */
    public static class DeleteResult {
        private final long deletedCount;
        
        public DeleteResult(long deletedCount) {
            this.deletedCount = deletedCount;
        }
        
        public long getDeletedCount() {
            return deletedCount;
        }
    }
}
