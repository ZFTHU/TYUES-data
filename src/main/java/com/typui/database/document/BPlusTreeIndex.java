package com.typui.database.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * B+树索引实现
 * 支持高效的范围查询和快速查找
 */
public class BPlusTreeIndex {
    
    private static final Logger logger = LoggerFactory.getLogger(BPlusTreeIndex.class);
    private static final int DEFAULT_ORDER = 64;
    
    private final String fieldName;
    private final int order;
    private final boolean unique;
    private Node root;
    private int size;
    
    /**
     * 索引节点基类
     */
    private abstract class Node {
        protected List<Object> keys;
        protected int keyCount;
        
        abstract Object find(Object key);
        abstract void insert(Object key, String docId, Map<String, Object> doc);
        abstract boolean delete(Object key, String docId);
        abstract List<Map<String, Object>> rangeQuery(Object min, Object max, boolean includeMin, boolean includeMax);
    }
    
    /**
     * 叶子节点
     */
    private class LeafNode extends Node {
        private List<Set<String>> docIds;
        private Map<Object, Map<String, Map<String, Object>>> dataMap;
        private LeafNode next;
        private LeafNode prev;
        
        LeafNode() {
            this.keys = new ArrayList<>();
            this.docIds = new ArrayList<>();
            this.dataMap = new HashMap<>();
            this.keyCount = 0;
        }
        
        @Override
        Object find(Object key) {
            int idx = Collections.binarySearch(keys, key, BPlusTreeIndex.this::compareKeys);
            if (idx >= 0) {
                return docIds.get(idx);
            }
            return null;
        }
        
        @Override
        void insert(Object key, String docId, Map<String, Object> doc) {
            int idx = Collections.binarySearch(keys, key, BPlusTreeIndex.this::compareKeys);
            if (idx >= 0) {
                if (unique && !docIds.get(idx).isEmpty()) {
                    throw new RuntimeException("唯一索引冲突: " + fieldName + " = " + key);
                }
                docIds.get(idx).add(docId);
                dataMap.computeIfAbsent(key, k -> new HashMap<>()).put(docId, doc);
            } else {
                int insertPos = -idx - 1;
                keys.add(insertPos, key);
                Set<String> idSet = new HashSet<>();
                idSet.add(docId);
                docIds.add(insertPos, idSet);
                dataMap.computeIfAbsent(key, k -> new HashMap<>()).put(docId, doc);
                keyCount++;
                
                if (keys.size() > order) {
                    split();
                }
            }
        }
        
        @Override
        boolean delete(Object key, String docId) {
            int idx = Collections.binarySearch(keys, key, BPlusTreeIndex.this::compareKeys);
            if (idx >= 0) {
                Set<String> ids = docIds.get(idx);
                boolean removed = ids.remove(docId);
                if (ids.isEmpty()) {
                    keys.remove(idx);
                    docIds.remove(idx);
                    dataMap.remove(key);
                    keyCount--;
                } else {
                    Map<String, Map<String, Object>> keyData = dataMap.get(key);
                    if (keyData != null) {
                        keyData.remove(docId);
                    }
                }
                return removed;
            }
            return false;
        }
        
        @Override
        List<Map<String, Object>> rangeQuery(Object min, Object max, boolean includeMin, boolean includeMax) {
            List<Map<String, Object>> result = new ArrayList<>();
            
            for (int i = 0; i < keys.size(); i++) {
                Object key = keys.get(i);
                
                if (inRange(key, min, max, includeMin, includeMax)) {
                    Map<String, Map<String, Object>> keyData = dataMap.get(key);
                    if (keyData != null) {
                        result.addAll(keyData.values());
                    }
                }
            }
            
            return result;
        }
        
        void split() {
            int mid = keys.size() / 2;
            
            LeafNode newLeaf = new LeafNode();
            
            newLeaf.keys.addAll(keys.subList(mid, keys.size()));
            newLeaf.docIds.addAll(docIds.subList(mid, docIds.size()));
            
            for (Object key : newLeaf.keys) {
                Map<String, Map<String, Object>> data = dataMap.get(key);
                if (data != null) {
                    newLeaf.dataMap.put(key, data);
                }
            }
            
            keys.subList(mid, keys.size()).clear();
            docIds.subList(mid, docIds.size()).clear();
            
            for (Object key : newLeaf.keys) {
                dataMap.remove(key);
            }
            
            keyCount = keys.size();
            newLeaf.keyCount = newLeaf.keys.size();
            
            newLeaf.next = this.next;
            newLeaf.prev = this;
            if (this.next != null) {
                this.next.prev = newLeaf;
            }
            this.next = newLeaf;
            
            if (root == this) {
                InternalNode newRoot = new InternalNode();
                newRoot.keys.add(newLeaf.keys.get(0));
                newRoot.children.add(this);
                newRoot.children.add(newLeaf);
                newRoot.keyCount = 1;
                root = newRoot;
            } else {
                ((InternalNode) findParent()).insertChild(newLeaf.keys.get(0), newLeaf);
            }
        }
    }
    
    /**
     * 内部节点
     */
    private class InternalNode extends Node {
        private List<Node> children;
        
        InternalNode() {
            this.keys = new ArrayList<>();
            this.children = new ArrayList<>();
            this.keyCount = 0;
        }
        
        @Override
        Object find(Object key) {
            int idx = findChildIndex(key);
            return children.get(idx).find(key);
        }
        
        @Override
        void insert(Object key, String docId, Map<String, Object> doc) {
            int idx = findChildIndex(key);
            children.get(idx).insert(key, docId, doc);
        }
        
        @Override
        boolean delete(Object key, String docId) {
            int idx = findChildIndex(key);
            return children.get(idx).delete(key, docId);
        }
        
        @Override
        List<Map<String, Object>> rangeQuery(Object min, Object max, boolean includeMin, boolean includeMax) {
            List<Map<String, Object>> result = new ArrayList<>();
            
            for (Node child : children) {
                result.addAll(child.rangeQuery(min, max, includeMin, includeMax));
            }
            
            return result;
        }
        
        void insertChild(Object key, Node child) {
            int idx = Collections.binarySearch(keys, key, BPlusTreeIndex.this::compareKeys);
            if (idx < 0) {
                idx = -idx - 1;
            }
            
            keys.add(idx, key);
            children.add(idx + 1, child);
            keyCount++;
            
            if (keys.size() > order) {
                split();
            }
        }
        
        private int findChildIndex(Object key) {
            int idx = Collections.binarySearch(keys, key, BPlusTreeIndex.this::compareKeys);
            if (idx >= 0) {
                return idx + 1;
            } else {
                return -idx - 1;
            }
        }
        
        void split() {
            int mid = keys.size() / 2;
            Object midKey = keys.get(mid);
            
            InternalNode newNode = new InternalNode();
            
            newNode.keys.addAll(keys.subList(mid + 1, keys.size()));
            newNode.children.addAll(children.subList(mid + 1, children.size()));
            
            keys.subList(mid, keys.size()).clear();
            children.subList(mid + 1, children.size()).clear();
            
            keyCount = keys.size();
            newNode.keyCount = newNode.keys.size();
            
            if (root == this) {
                InternalNode newRoot = new InternalNode();
                newRoot.keys.add(midKey);
                newRoot.children.add(this);
                newRoot.children.add(newNode);
                newRoot.keyCount = 1;
                root = newRoot;
            } else {
                ((InternalNode) findParent()).insertChild(midKey, newNode);
            }
        }
    }
    
    public BPlusTreeIndex(String fieldName) {
        this(fieldName, DEFAULT_ORDER, false);
    }
    
    public BPlusTreeIndex(String fieldName, int order, boolean unique) {
        this.fieldName = fieldName;
        this.order = order;
        this.unique = unique;
        this.root = new LeafNode();
        this.size = 0;
    }
    
    /**
     * 插入文档到索引
     */
    public void insert(Map<String, Object> doc) {
        Object key = doc.get(fieldName);
        if (key == null) {
            return;
        }
        
        String docId = (String) doc.get("_id");
        root.insert(key, docId, doc);
        size++;
    }
    
    /**
     * 从索引删除文档
     */
    public boolean delete(Map<String, Object> doc) {
        Object key = doc.get(fieldName);
        if (key == null) {
            return false;
        }
        
        String docId = (String) doc.get("_id");
        if (root.delete(key, docId)) {
            size--;
            return true;
        }
        return false;
    }
    
    /**
     * 精确查找
     */
    public List<Map<String, Object>> find(Object key) {
        LeafNode leaf = findLeaf(key);
        if (leaf != null) {
            Map<String, Map<String, Object>> keyData = leaf.dataMap.get(key);
            if (keyData != null) {
                return new ArrayList<>(keyData.values());
            }
        }
        return new ArrayList<>();
    }
    
    /**
     * 范围查询
     */
    public List<Map<String, Object>> rangeQuery(Object min, Object max) {
        return rangeQuery(min, max, true, true);
    }
    
    /**
     * 范围查询（带边界控制）
     */
    public List<Map<String, Object>> rangeQuery(Object min, Object max, boolean includeMin, boolean includeMax) {
        return root.rangeQuery(min, max, includeMin, includeMax);
    }
    
    /**
     * 查找叶子节点
     */
    private LeafNode findLeaf(Object key) {
        Node current = root;
        while (current instanceof InternalNode) {
            current = (Node) ((InternalNode) current).find(key);
        }
        return (LeafNode) current;
    }
    
    /**
     * 查找父节点
     */
    private Node findParent() {
        return findParent(root, null);
    }
    
    private Node findParent(Node current, Node parent) {
        if (current instanceof LeafNode) {
            return parent;
        }
        
        InternalNode internal = (InternalNode) current;
        for (Node child : internal.children) {
            Node found = findParent(child, current);
            if (found != null) {
                return found;
            }
        }
        
        return null;
    }
    
    /**
     * 比较键值
     */
    @SuppressWarnings("unchecked")
    private int compareKeys(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        
        if (a instanceof Number && b instanceof Number) {
            double diff = ((Number) a).doubleValue() - ((Number) b).doubleValue();
            return Double.compare(diff, 0);
        }
        
        if (a instanceof Comparable && b instanceof Comparable) {
            return ((Comparable<Object>) a).compareTo(b);
        }
        
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
    
    /**
     * 检查是否在范围内
     */
    private boolean inRange(Object key, Object min, Object max, boolean includeMin, boolean includeMax) {
        int cmpMin = (min == null) ? 1 : compareKeys(key, min);
        int cmpMax = (max == null) ? -1 : compareKeys(key, max);
        
        boolean afterMin = includeMin ? cmpMin >= 0 : cmpMin > 0;
        boolean beforeMax = includeMax ? cmpMax <= 0 : cmpMax < 0;
        
        return afterMin && beforeMax;
    }
    
    /**
     * 获取索引大小
     */
    public int size() {
        return size;
    }
    
    /**
     * 获取字段名
     */
    public String getFieldName() {
        return fieldName;
    }
    
    /**
     * 是否唯一索引
     */
    public boolean isUnique() {
        return unique;
    }
    
    /**
     * 清空索引
     */
    public void clear() {
        root = new LeafNode();
        size = 0;
    }
}
