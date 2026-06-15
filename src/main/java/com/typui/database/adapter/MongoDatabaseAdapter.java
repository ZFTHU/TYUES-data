package com.typui.database.adapter;

import com.typui.database.document.DocumentCollection;
import com.typui.database.document.DocumentDatabase;
import com.typui.database.document.DocumentServer;

import java.io.Closeable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MongoDB兼容数据库适配器
 * 模拟MongoDB的MongoDatabase接口
 */
public class MongoDatabaseAdapter implements Closeable {
    
    private final DocumentDatabase database;
    private final Map<String, MongoCollectionAdapter> collectionCache;
    
    public MongoDatabaseAdapter(DocumentServer server, String databaseName) {
        this.database = server.getDatabase(databaseName);
        if (this.database == null) {
            throw new IllegalArgumentException("数据库不存在: " + databaseName);
        }
        this.collectionCache = new HashMap<>();
    }
    
    public MongoDatabaseAdapter(DocumentDatabase database) {
        this.database = database;
        this.collectionCache = new HashMap<>();
    }
    
    /**
     * 获取集合
     */
    public MongoCollectionAdapter getCollection(String collectionName) {
        return collectionCache.computeIfAbsent(collectionName, 
            name -> new MongoCollectionAdapter(database, name));
    }
    
    /**
     * 创建集合
     */
    public void createCollection(String collectionName) {
        database.createCollection(collectionName);
    }
    
    /**
     * 删除集合
     */
    public void dropCollection(String collectionName) {
        database.dropCollection(collectionName);
        collectionCache.remove(collectionName);
    }
    
    /**
     * 列出所有集合
     */
    public List<String> listCollectionNames() {
        return database.listCollections();
    }
    
    /**
     * 获取数据库名称
     */
    public String getName() {
        return database.getName();
    }
    
    /**
     * 运行命令
     */
    public Map<String, Object> runCommand(Map<String, Object> command) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", 1.0);
        result.put("operation", "success");
        return result;
    }
    
    @Override
    public void close() {
        // 清理缓存
        collectionCache.clear();
    }
}
