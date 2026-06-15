package com.typui.database.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事务管理器
 * 支持ACID事务特性
 */
public class TransactionManager {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionManager.class);
    private static final AtomicLong transactionIdGenerator = new AtomicLong(0);
    
    private final Map<Long, Transaction> activeTransactions;
    private final Map<String, DocumentCollection> collections;
    
    public TransactionManager(Map<String, DocumentCollection> collections) {
        this.activeTransactions = new ConcurrentHashMap<>();
        this.collections = collections;
    }
    
    /**
     * 开始事务
     */
    public long beginTransaction() {
        long txId = transactionIdGenerator.incrementAndGet();
        Transaction tx = new Transaction(txId);
        activeTransactions.put(txId, tx);
        
        logger.debug("事务开始: {}", txId);
        return txId;
    }
    
    /**
     * 提交事务
     */
    public boolean commit(long txId) {
        Transaction tx = activeTransactions.remove(txId);
        if (tx == null) {
            logger.warn("事务不存在: {}", txId);
            return false;
        }
        
        try {
            tx.applyChanges(collections);
            logger.debug("事务提交成功: {}", txId);
            return true;
        } catch (Exception e) {
            logger.error("事务提交失败: {}", txId, e);
            rollback(txId);
            return false;
        }
    }
    
    /**
     * 回滚事务
     */
    public boolean rollback(long txId) {
        Transaction tx = activeTransactions.remove(txId);
        if (tx == null) {
            logger.warn("事务不存在: {}", txId);
            return false;
        }
        
        tx.clear();
        logger.debug("事务回滚成功: {}", txId);
        return true;
    }
    
    /**
     * 在事务中插入文档
     */
    public void insert(long txId, String collectionName, Map<String, Object> document) {
        Transaction tx = activeTransactions.get(txId);
        if (tx == null) {
            throw new RuntimeException("事务不存在: " + txId);
        }
        
        tx.addOperation(new TransactionOperation(
            OperationType.INSERT, collectionName, document, null
        ));
    }
    
    /**
     * 在事务中更新文档
     */
    public void update(long txId, String collectionName, String docId, Map<String, Object> update) {
        Transaction tx = activeTransactions.get(txId);
        if (tx == null) {
            throw new RuntimeException("事务不存在: " + txId);
        }
        
        DocumentCollection collection = collections.get(collectionName);
        if (collection == null) {
            throw new RuntimeException("集合不存在: " + collectionName);
        }
        
        Map<String, Object> oldDoc = collection.findById(docId);
        if (oldDoc == null) {
            throw new RuntimeException("文档不存在: " + docId);
        }
        
        tx.addOperation(new TransactionOperation(
            OperationType.UPDATE, collectionName, oldDoc, update
        ));
    }
    
    /**
     * 在事务中删除文档
     */
    public void delete(long txId, String collectionName, String docId) {
        Transaction tx = activeTransactions.get(txId);
        if (tx == null) {
            throw new RuntimeException("事务不存在: " + txId);
        }
        
        DocumentCollection collection = collections.get(collectionName);
        if (collection == null) {
            throw new RuntimeException("集合不存在: " + collectionName);
        }
        
        Map<String, Object> oldDoc = collection.findById(docId);
        if (oldDoc == null) {
            throw new RuntimeException("文档不存在: " + docId);
        }
        
        tx.addOperation(new TransactionOperation(
            OperationType.DELETE, collectionName, oldDoc, null
        ));
    }
    
    /**
     * 获取活跃事务数量
     */
    public int getActiveTransactionCount() {
        return activeTransactions.size();
    }
    
    /**
     * 清理超时事务
     */
    public void cleanupTimeoutTransactions(long timeoutMs) {
        long now = System.currentTimeMillis();
        activeTransactions.entrySet().removeIf(entry -> {
            if (now - entry.getValue().getStartTime() > timeoutMs) {
                logger.warn("清理超时事务: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
    
    /**
     * 操作类型枚举
     */
    private enum OperationType {
        INSERT, UPDATE, DELETE
    }
    
    /**
     * 事务操作类
     */
    private static class TransactionOperation {
        final OperationType type;
        final String collectionName;
        final Map<String, Object> document;
        final Map<String, Object> update;
        
        TransactionOperation(OperationType type, String collectionName, 
                           Map<String, Object> document, Map<String, Object> update) {
            this.type = type;
            this.collectionName = collectionName;
            this.document = document;
            this.update = update;
        }
    }
    
    /**
     * 事务类
     */
    private class Transaction {
        private final long txId;
        private final long startTime;
        private final List<TransactionOperation> operations;
        private final Map<String, Map<String, Object>> locks;
        
        Transaction(long txId) {
            this.txId = txId;
            this.startTime = System.currentTimeMillis();
            this.operations = new ArrayList<>();
            this.locks = new HashMap<>();
        }
        
        void addOperation(TransactionOperation operation) {
            operations.add(operation);
            
            String docId = (String) operation.document.get("_id");
            if (docId != null) {
                locks.put(operation.collectionName + ":" + docId, operation.document);
            }
        }
        
        void applyChanges(Map<String, DocumentCollection> collections) {
            for (TransactionOperation op : operations) {
                DocumentCollection collection = collections.get(op.collectionName);
                if (collection == null) {
                    throw new RuntimeException("集合不存在: " + op.collectionName);
                }
                
                switch (op.type) {
                    case INSERT:
                        collection.insert(op.document);
                        break;
                    case UPDATE:
                        String updateId = (String) op.document.get("_id");
                        collection.updateById(updateId, op.update);
                        break;
                    case DELETE:
                        String deleteId = (String) op.document.get("_id");
                        collection.deleteById(deleteId);
                        break;
                }
            }
        }
        
        void clear() {
            operations.clear();
            locks.clear();
        }
        
        long getStartTime() {
            return startTime;
        }
    }
}
