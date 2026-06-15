package com.typui.database.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档数据库管理器
 * 类似MongoDB的数据库概念
 */
public class DocumentDatabase {
    
    private static final Logger logger = LoggerFactory.getLogger(DocumentDatabase.class);
    
    private final String name;
    private final File dataDir;
    private final Map<String, DocumentCollection> collections;
    
    public DocumentDatabase(String name, File dataDir) {
        this.name = name;
        this.dataDir = new File(dataDir, name);
        this.collections = new ConcurrentHashMap<>();
        
        initializeDatabase();
    }
    
    /**
     * 初始化数据库
     */
    private void initializeDatabase() {
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            logger.info("数据库目录创建成功: {}", dataDir.getAbsolutePath());
        }
        
        loadCollections();
    }
    
    /**
     * 加载所有集合
     */
    private void loadCollections() {
        File[] dirs = dataDir.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                String collectionName = dir.getName();
                collections.put(collectionName, new DocumentCollection(collectionName, dataDir));
                logger.info("集合加载成功: {}", collectionName);
            }
        }
    }
    
    /**
     * 获取或创建集合
     */
    public DocumentCollection getCollection(String collectionName) {
        return collections.computeIfAbsent(collectionName, 
            name -> new DocumentCollection(name, dataDir));
    }
    
    /**
     * 创建集合
     */
    public DocumentCollection createCollection(String collectionName) {
        if (collections.containsKey(collectionName)) {
            logger.warn("集合已存在: {}", collectionName);
            return collections.get(collectionName);
        }
        
        DocumentCollection collection = new DocumentCollection(collectionName, dataDir);
        collections.put(collectionName, collection);
        logger.info("集合创建成功: {}", collectionName);
        
        return collection;
    }
    
    /**
     * 删除集合
     */
    public boolean dropCollection(String collectionName) {
        DocumentCollection collection = collections.remove(collectionName);
        if (collection != null) {
            collection.clear();
            File collectionDir = new File(dataDir, collectionName);
            if (collectionDir.exists()) {
                deleteDirectory(collectionDir);
            }
            logger.info("集合删除成功: {}", collectionName);
            return true;
        }
        return false;
    }
    
    /**
     * 列出所有集合
     */
    public List<String> listCollections() {
        return new ArrayList<>(collections.keySet());
    }
    
    /**
     * 获取集合名称集合
     */
    public Set<String> getCollectionNames() {
        return collections.keySet();
    }
    
    /**
     * 检查集合是否存在
     */
    public boolean collectionExists(String collectionName) {
        return collections.containsKey(collectionName);
    }
    
    /**
     * 获取数据库名称
     */
    public String getName() {
        return name;
    }
    
    /**
     * 获取数据目录
     */
    public File getDataDir() {
        return dataDir;
    }
    
    /**
     * 删除目录
     */
    private void deleteDirectory(File dir) {
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
