package com.typui.database.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typui.database.ConfigManager;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据库管理工具
 * 用于读取、修改、删除、查看存储库
 */
public class DatabaseManagerTool {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static ConfigManager configManager;
    
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        
        configManager = new ConfigManager();
        String command = args[0].toLowerCase();
        
        switch (command) {
            case "list":
                handleList(args);
                break;
            case "view":
                handleView(args);
                break;
            case "delete":
                handleDelete(args);
                break;
            case "export":
                handleExport(args);
                break;
            case "import":
                handleImport(args);
                break;
            case "info":
                handleInfo(args);
                break;
            case "clean":
                handleClean(args);
                break;
            default:
                System.out.println("未知命令: " + command);
                printUsage();
        }
    }
    
    /**
     * 打印使用帮助
     */
    private static void printUsage() {
        System.out.println("");
        System.out.println("TYPUI Database Manager v2.0");
        System.out.println("");
        System.out.println("用法: java -jar typui-database.jar <命令> [参数]");
        System.out.println("");
        System.out.println("命令:");
        System.out.println("  list              列出所有数据库和集合");
        System.out.println("  view <db> [col]   查看数据库/集合内容");
        System.out.println("  delete <db>       删除指定数据库");
        System.out.println("  export <file>     导出数据到JSON文件");
        System.out.println("  import <file>     从JSON文件导入数据");
        System.out.println("  info              显示系统信息");
        System.out.println("  clean             清理无效数据");
        System.out.println("");
        System.out.println("示例:");
        System.out.println("  java -jar dbtool.jar list");
        System.out.println("  java -jar dbtool.jar view mydb users");
        System.out.println("  java -jar dbtool.jar export backup.json");
        System.out.println("  java -jar dbtool.jar import backup.json");
        System.out.println("");
    }
    
    /**
     * 处理列出命令
     */
    private static void handleList(String[] args) {
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir);
        
        if (!dbPath.exists()) {
            System.out.println("数据库目录不存在");
            return;
        }
        
        System.out.println("");
        System.out.println("=== 数据库列表 ===");
        System.out.println("");
        
        File[] databases = dbPath.listFiles(File::isDirectory);
        if (databases != null) {
            for (File database : databases) {
                if (database.getName().startsWith(".")) continue;
                
                long docCount = countDocuments(database);
                long size = calculateDirSize(database);
                
                System.out.println("📁 " + database.getName());
                System.out.println("   文档数: " + docCount + " | 大小: " + formatSize(size));
                
                File[] collections = database.listFiles(File::isDirectory);
                if (collections != null) {
                    for (File collection : collections) {
                        int count = countFiles(collection, ".json");
                        System.out.println("   └─ 📂 " + collection.getName() + " (" + count + ")");
                    }
                }
                System.out.println("");
            }
        } else {
            System.out.println("(空)");
        }
    }
    
    /**
     * 处理查看命令
     */
    @SuppressWarnings("unchecked")
    private static void handleView(String[] args) {
        if (args.length < 2) {
            System.out.println("用法: view <数据库名> [集合名]");
            return;
        }
        
        String dbName = args[1];
        String colName = args.length > 2 ? args[2] : null;
        
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir, dbName);
        
        if (!dbPath.exists()) {
            System.out.println("数据库不存在: " + dbName);
            return;
        }
        
        if (colName != null) {
            viewCollection(dbPath, colName);
        } else {
            viewDatabase(dbPath);
        }
    }
    
    /**
     * 查看数据库
     */
    private static void viewDatabase(File dbPath) {
        System.out.println("");
        System.out.println("=== 数据库: " + dbPath.getName() + " ===");
        System.out.println("");
        
        File[] collections = dbPath.listFiles(File::isDirectory);
        if (collections != null && collections.length > 0) {
            for (File collection : collections) {
                int count = countFiles(collection, ".json");
                System.out.println("📂 " + collection.getName() + " (" + count + " 文档)");
            }
        } else {
            System.out.println("(无集合)");
        }
        System.out.println("");
    }
    
    /**
     * 查看集合
     */
    @SuppressWarnings("unchecked")
    private static void viewCollection(File dbPath, String colName) {
        File colPath = new File(dbPath, colName);
        
        if (!colPath.exists()) {
            System.out.println("集合不存在: " + colName);
            return;
        }
        
        System.out.println("");
        System.out.println("=== 集合: " + colName + " ===");
        System.out.println("");
        
        File[] files = colPath.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null && files.length > 0) {
            int limit = 20;
            int shown = 0;
            
            for (File file : files) {
                if (shown >= limit) {
                    System.out.println("... 还有 " + (files.length - limit) + " 个文档");
                    break;
                }
                
                try {
                    Map<String, Object> doc = objectMapper.readValue(file, Map.class);
                    System.out.println("--- 文档 #" + (shown + 1) + " ---");
                    prettyPrint(doc, 0);
                    System.out.println("");
                } catch (Exception e) {
                    System.out.println("读取失败: " + file.getName());
                }
                shown++;
            }
            
            System.out.println("共 " + files.length + " 个文档");
        } else {
            System.out.println("(空)");
        }
        System.out.println("");
    }
    
    /**
     * 处理删除命令
     */
    private static void handleDelete(String[] args) {
        if (args.length < 2) {
            System.out.println("用法: delete <数据库名>");
            return;
        }
        
        String dbName = args[1];
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir, dbName);
        
        if (!dbPath.exists()) {
            System.out.println("数据库不存在: " + dbName);
            return;
        }
        
        System.out.print("确定要删除数据库 '" + dbName + "' 吗？(y/N): ");
        Scanner scanner = new Scanner(System.in);
        String confirm = scanner.nextLine().trim();
        
        if (confirm.equalsIgnoreCase("y")) {
            deleteDirectory(dbPath);
            System.out.println("✓ 数据库已删除: " + dbName);
        } else {
            System.out.println("已取消");
        }
    }
    
    /**
     * 处理导出命令
     */
    @SuppressWarnings("unchecked")
    private static void handleExport(String[] args) {
        if (args.length < 2) {
            System.out.println("用法: export <输出文件>");
            return;
        }
        
        String outputFile = args[1];
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir);
        
        Map<String, Object> exportData = new HashMap<>();
        exportData.put("timestamp", System.currentTimeMillis());
        exportData.put("databaseName", configManager.getDatabaseName());
        exportData.put("version", "2.0");
        
        Map<String, Object> allDatabases = new HashMap<>();
        
        if (dbPath.exists() && dbPath.isDirectory()) {
            File[] databases = dbPath.listFiles(File::isDirectory);
            if (databases != null) {
                for (File database : databases) {
                    if (database.getName().startsWith(".")) continue;
                    
                    Map<String, Object> dbData = new HashMap<>();
                    
                    File[] collections = database.listFiles(File::isDirectory);
                    if (collections != null) {
                        for (File collection : collections) {
                            List<Map<String, Object>> documents = new ArrayList<>();
                            
                            File[] jsonFiles = collection.listFiles((dir, name) -> name.endsWith(".json"));
                            if (jsonFiles != null) {
                                for (File jsonFile : jsonFiles) {
                                    try {
                                        Map<String, Object> doc = objectMapper.readValue(jsonFile, Map.class);
                                        documents.add(doc);
                                    } catch (Exception e) {
                                        // 跳过损坏的文件
                                    }
                                }
                            }
                            
                            dbData.put(collection.getName(), documents);
                        }
                    }
                    
                    allDatabases.put(database.getName(), dbData);
                }
            }
        }
        
        exportData.put("data", allDatabases);
        
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputFile), exportData);
            System.out.println("✓ 导出成功: " + outputFile);
            System.out.println("  大小: " + formatSize(new File(outputFile).length()));
        } catch (Exception e) {
            System.out.println("导出失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理导入命令
     */
    @SuppressWarnings("unchecked")
    private static void handleImport(String[] args) {
        if (args.length < 2) {
            System.out.println("用法: import <输入文件>");
            return;
        }
        
        String inputFile = args[1];
        File file = new File(inputFile);
        
        if (!file.exists()) {
            System.out.println("文件不存在: " + inputFile);
            return;
        }
        
        System.out.print("导入将覆盖现有数据，确定吗？(y/N): ");
        Scanner scanner = new Scanner(System.in);
        String confirm = scanner.nextLine().trim();
        
        if (!confirm.equalsIgnoreCase("y")) {
            System.out.println("已取消");
            return;
        }
        
        try {
            Map<String, Object> importData = objectMapper.readValue(file, Map.class);
            Map<String, Object> data = (Map<String, Object>) importData.get("data");
            
            if (data == null) {
                System.out.println("无效的数据格式");
                return;
            }
            
            String dbDir = configManager.getDocumentDbDataDir();
            int totalDocs = 0;
            
            for (Map.Entry<String, Object> dbEntry : data.entrySet()) {
                String dbName = dbEntry.getKey();
                Map<String, Object> collections = (Map<String, Object>) dbEntry.getValue();
                
                File databaseDir = new File(dbDir, dbName);
                if (!databaseDir.exists()) {
                    databaseDir.mkdirs();
                }
                
                for (Map.Entry<String, Object> colEntry : collections.entrySet()) {
                    String colName = colEntry.getKey();
                    List<Map<String, Object>> documents = (List<Map<String, Object>>) colEntry.getValue();
                    
                    File collectionDir = new File(databaseDir, colName);
                    if (!collectionDir.exists()) {
                        collectionDir.mkdirs();
                    } else {
                        cleanDirectory(collectionDir);
                    }
                    
                    for (Map<String, Object> doc : documents) {
                        String id = (String) doc.get("_id");
                        if (id != null) {
                            File docFile = new File(collectionDir, id + ".json");
                            objectMapper.writerWithDefaultPrettyPrinter().writeValue(docFile, doc);
                            totalDocs++;
                        }
                    }
                }
            }
            
            System.out.println("✓ 导入成功");
            System.out.println("  数据库: " + data.size());
            System.out.println("  文档数: " + totalDocs);
        } catch (Exception e) {
            System.out.println("导入失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理信息命令
     */
    private static void handleInfo(String[] args) {
        System.out.println("");
        System.out.println("=== 系统信息 ===");
        System.out.println("");
        System.out.println("版本: TYPUI Database v2.0");
        System.out.println("数据库名: " + configManager.getDatabaseName());
        System.out.println("文档端口: " + configManager.getDocumentDbPort());
        System.out.println("存储端口: " + configManager.getFileStoragePort());
        System.out.println("");
        
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir);
        
        if (dbPath.exists()) {
            File[] databases = dbPath.listFiles(File::isDirectory);
            int dbCount = databases != null ? databases.length : 0;
            long totalDocs = 0;
            long totalSize = 0;
            
            if (databases != null) {
                for (File db : databases) {
                    if (!db.getName().startsWith(".")) {
                        totalDocs += countDocuments(db);
                        totalSize += calculateDirSize(db);
                    }
                }
            }
            
            System.out.println("数据库数: " + dbCount);
            System.out.println("总文档数: " + totalDocs);
            System.out.println("数据大小: " + formatSize(totalSize));
        }
        
        Path snapshotPath = Paths.get(".snapshots");
        if (Files.exists(snapshotPath)) {
            try {
                long snapCount = Files.list(snapshotPath).count();
                System.out.println("快照数: " + snapCount);
            } catch (Exception e) {}
        }
        System.out.println("");
    }
    
    /**
     * 清理命令
     */
    private static void handleClean(String[] args) {
        System.out.print("清理将删除所有无效数据，确定吗？(y/N): ");
        Scanner scanner = new Scanner(System.in);
        String confirm = scanner.nextLine().trim();
        
        if (!confirm.equalsIgnoreCase("y")) {
            System.out.println("已取消");
            return;
        }
        
        String dbDir = configManager.getDocumentDbDataDir();
        File dbPath = new File(dbDir);
        
        int cleaned = 0;
        
        if (dbPath.exists() && dbPath.isDirectory()) {
            File[] databases = dbPath.listFiles(File::isDirectory);
            if (databases != null) {
                for (File database : databases) {
                    if (database.getName().startsWith(".")) continue;
                    
                    File[] collections = database.listFiles(File::isDirectory);
                    if (collections != null) {
                        for (File collection : collections) {
                            File[] files = collection.listFiles((dir, name) -> name.endsWith(".json"));
                            if (files != null) {
                                for (File file : files) {
                                    try {
                                        objectMapper.readValue(file, Map.class);
                                    } catch (Exception e) {
                                        file.delete();
                                        cleaned++;
                                    }
                                }
                            }
                            
                            File[] subDirs = collection.listFiles(File::isDirectory);
                            if (subDirs != null) {
                                for (File dir : subDirs) {
                                    if (dir.listFiles() == null || dir.listFiles().length == 0) {
                                        dir.delete();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        System.out.println("✓ 清理完成，删除了 " + cleaned + " 个无效文件");
    }
    
    /**
     * 统计文档数量
     */
    private static long countDocuments(File directory) {
        long count = 0;
        File[] collections = directory.listFiles(File::isDirectory);
        if (collections != null) {
            for (File col : collections) {
                count += countFiles(col, ".json");
            }
        }
        return count;
    }
    
    /**
     * 统计文件数量
     */
    private static int countFiles(File directory, String extension) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(extension));
        return files != null ? files.length : 0;
    }
    
    /**
     * 计算目录大小
     */
    private static long calculateDirSize(File directory) {
        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else {
                    size += calculateDirSize(file);
                }
            }
        }
        return size;
    }
    
    /**
     * 格式化大小
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    /**
     * 递归删除目录
     */
    private static void deleteDirectory(File dir) {
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
    
    /**
     * 清空目录
     */
    private static void cleanDirectory(File dir) {
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
    }
    
    /**
     * 格式化打印Map
     */
    @SuppressWarnings("unchecked")
    private static void prettyPrint(Map<String, Object> map, int indent) {
        String prefix = "  ".repeat(indent);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                System.out.println(prefix + entry.getKey() + ": {");
                prettyPrint((Map<String, Object>) value, indent + 1);
                System.out.println(prefix + "}");
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                System.out.println(prefix + entry.getKey() + ": [");
                for (Object item : list) {
                    if (item instanceof Map) {
                        System.out.println(prefix + "  {");
                        prettyPrint((Map<String, Object>) item, indent + 2);
                        System.out.println(prefix + "  }");
                    } else {
                        System.out.println(prefix + "  " + item);
                    }
                }
                System.out.println(prefix + "]");
            } else {
                System.out.println(prefix + entry.getKey() + ": " + truncate(value.toString(), 100));
            }
        }
    }
    
    /**
     * 截断字符串
     */
    private static String truncate(String str, int maxLen) {
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }
}
