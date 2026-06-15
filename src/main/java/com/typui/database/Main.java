package com.typui.database;

import com.typui.database.admin.AdminServer;
import com.typui.database.auth.AuthService;
import com.typui.database.backup.BackupManager;
import com.typui.database.document.DocumentServer;
import com.typui.database.logging.LogManager;
import com.typui.database.storage.StorageServer;
import com.typui.database.util.StartupBanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主程序入口。
 *
 * 启动的服务：
 *   1. 文档数据库（HTTP，默认端口 27016）
 *   2. 文件存储（HTTP，默认端口 9001）
 *   3. MySQL 线协议（TCP，默认端口 3306）—— 通过标准 MySQL 客户端/驱动连接
 *   4. MongoDB 线协议（TCP，默认端口 27017）—— 通过标准 MongoDB 驱动连接
 *   5. S3/MinIO 兼容对象存储（HTTP，默认端口 9000）—— 通过 AWS SDK 连接
 *
 * 所有文档数据库 / MySQL / MongoDB 使用的文档集合共享相同的底层存储
 * （文件系统上加密存储，AES-256-GCM）。
 * 所有对象存储 / S3 也共享同一份加密文件存储。
 *
 * 默认的根账户/密码：
 *   username = root
 *   password = ZM135790..
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static final String PUBLIC_VERSION = "3.4";
    public static final String FULL_VERSION = "3.4.2567.24";
    public static final String BUILD_VERSION = "4.2";

    private static ConfigManager configManager;
    private static AuthService authService;
    private static DocumentServer documentServer;
    private static StorageServer storageServer;
    private static BackupManager backupManager;
    private static AdminServer adminServer;
    private static com.typui.database.adapter.MysqlProtocolServer mysqlServer;
    private static com.typui.database.adapter.MongoWireProtocolServer mongoServer;
    private static com.typui.database.adapter.S3CompatibleServer s3Server;

    private static boolean backgroundMode = false;
    private static boolean isDaemon = false;
    private static ScheduledExecutorService snapshotScheduler;
    private static ScheduledExecutorService cleanupScheduler;
    private static final String PID_FILE = ".typui_db.pid";
    private static final String SNAPSHOT_DIR = ".snapshots";
    private static final String LOG_FILE = "typui_db.log";
    private static volatile boolean running = true;

    public static void main(String[] args) {
        parseArguments(args);

        if (isDaemon) {
            startDaemonMode();
        } else if (backgroundMode) {
            startBackgroundMode();
        } else {
            startForegroundMode();
        }
    }

    private static void parseArguments(String[] args) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase("-YC") || arg.equalsIgnoreCase("--background")) {
                backgroundMode = true;
            } else if (arg.equalsIgnoreCase("--daemon")) {
                isDaemon = true;
                backgroundMode = true;
            } else if (arg.equalsIgnoreCase("-h") || arg.equalsIgnoreCase("--help")) {
                printHelp();
                System.exit(0);
            } else if (arg.equalsIgnoreCase("-v") || arg.equalsIgnoreCase("--version")) {
                System.out.println("TYPUI Database v" + FULL_VERSION + " (Build " + BUILD_VERSION + ")");
                System.exit(0);
            }
        }
    }

    private static void printHelp() {
        System.out.println("TYPUI Database v" + FULL_VERSION + " (Build " + BUILD_VERSION + ")");
        System.out.println("Usage: java -jar typui-database.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -YC, --background   Run in background mode");
        System.out.println("  -h,  --help         Show this help message");
        System.out.println("  -v,  --version      Show version information");
        System.out.println();
        System.out.println("默认登录账户: root / ZM135790..");
        System.out.println("兼容协议: HTTP(JSON), MySQL(TCP 3306), MongoDB Wire(TCP 27017), S3/MinIO(TCP 9000)");
    }

    private static void startBackgroundMode() {
        try {
            String javaHome = System.getProperty("java.home");
            String javaExe = javaHome + File.separator + "bin" + File.separator + "java";

            List<String> command = new ArrayList<>();
            command.add(javaExe);
            command.add("-jar");
            command.add(findJarPath());
            command.add("--daemon");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File("."));
            pb.redirectOutput(new File(LOG_FILE));
            pb.redirectError(new File(LOG_FILE));
            Process process = pb.start();

            Thread.sleep(500);

            System.out.println("TYPUI Database started in background.");
            System.out.println("Log file: " + LOG_FILE);
            System.out.println("Terminal is ready for new commands.");

            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String findJarPath() {
        try {
            String path = Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            return new File(path).getAbsolutePath();
        } catch (Exception e) {
            return "build/libs/typui-database.jar";
        }
    }

    private static void startDaemonMode() {
        try {
            String pid = getCurrentPid();
            savePidFile(pid);

            File logFile = new File(LOG_FILE);
            FileOutputStream fos = new FileOutputStream(logFile, false);
            PrintStream ps = new PrintStream(fos, true, "UTF-8");
            System.setOut(ps);
            System.setErr(ps);

            startForegroundMode();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void startForegroundMode() {
        StartupBanner.print();

        initializeLogManager();
        initializeServices();
        startSnapshotScheduler();
        startCleanupScheduler();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownServices();
        }));

        printStartupInfo();
        startConsoleLoop();
    }

    private static void initializeLogManager() {
        try {
            LogManager.initialize(".", 100, 30, 100);
            logger.info("日志管理器初始化完成");
        } catch (Exception e) {
            logger.warn("日志管理器初始化失败: {}", e.getMessage());
        }
    }

    private static void initializeServices() {
        try {
            StartupBanner.printServiceStart("ConfigManager");
            configManager = new ConfigManager();
            StartupBanner.printServiceReady("ConfigManager", "localhost", 0);

            StartupBanner.printServiceStart("AuthService");
            File dataDir = new File(".");
            authService = new AuthService(
                    dataDir.getAbsolutePath(),
                    configManager.getAuthSecretKey(),
                    configManager.getTokenExpireHours(),
                    configManager.getAdminUsername(),
                    configManager.getAdminPassword()
            );
            StartupBanner.printServiceReady("AuthService", "localhost", 0);

            StartupBanner.printServiceStart("DocumentServer");
            documentServer = new DocumentServer(configManager, authService);
            documentServer.start();
            StartupBanner.printServiceReady("DocumentServer",
                    configManager.getDocumentDbHost(), configManager.getDocumentDbPort());

            StartupBanner.printServiceStart("StorageServer");
            storageServer = new StorageServer(configManager, authService);
            storageServer.start();
            StartupBanner.printServiceReady("StorageServer",
                    configManager.getFileStorageHost(), configManager.getFileStoragePort());

            StartupBanner.printServiceStart("BackupManager");
            backupManager = new BackupManager(configManager);
            StartupBanner.printServiceReady("BackupManager", "localhost", 0);

            StartupBanner.printServiceStart("AdminServer");
            adminServer = new AdminServer(configManager, documentServer, storageServer);
            adminServer.start();
            StartupBanner.printServiceReady("AdminServer",
                    configManager.getFileStorageHost(), configManager.getFileStoragePort() + 1);

            // ---- 新增协议适配服务器 ----
            StartupBanner.printServiceStart("MySQL-Protocol");
            mysqlServer = new com.typui.database.adapter.MysqlProtocolServer(
                    configManager,
                    documentServer.getDatabases()
            );
            if (configManager.isMysqlProtocolEnabled()) {
                mysqlServer.start();
                StartupBanner.printServiceReady("MySQL-Protocol",
                        "0.0.0.0", configManager.getMysqlPort());
            } else {
                logger.info("MySQL 协议已在 config.json 中禁用，跳过启动");
            }

            StartupBanner.printServiceStart("MongoDB-Wire");
            mongoServer = new com.typui.database.adapter.MongoWireProtocolServer(
                    configManager,
                    documentServer.getDatabases()
            );
            if (isMongoProtocolEnabledSafe(configManager)) {
                mongoServer.start();
                StartupBanner.printServiceReady("MongoDB-Wire",
                        "0.0.0.0", configManager.getMongodbPort());
            } else {
                logger.info("MongoDB 线协议已在 config.json 中禁用，跳过启动");
            }

            StartupBanner.printServiceStart("S3/MinIO-Compatible");
            s3Server = new com.typui.database.adapter.S3CompatibleServer(
                    configManager,
                    storageServer.getBuckets()
            );
            if (isS3ProtocolEnabledSafe(configManager)) {
                s3Server.start();
                StartupBanner.printServiceReady("S3/MinIO-Compatible",
                        "0.0.0.0", configManager.getS3Port());
            } else {
                logger.info("S3 协议已在 config.json 中禁用，跳过启动");
            }

            restoreFromSnapshotIfNeeded();

            System.out.println();
            System.out.println("  \u001B[32m  ✓ All services started successfully!\u001B[0m");
        } catch (Exception e) {
            StartupBanner.printServiceError("Services", e.getMessage());
            logger.error("服务启动失败", e);
            System.exit(1);
        }
    }

    // 一些新加入的协议开关判断（在旧 config.json 上未初始化时返回 true，保证默认开启）
    private static boolean isMongoProtocolEnabledSafe(ConfigManager cm) {
        try { return cm.isMongodbProtocolEnabled(); } catch (Exception e) { return true; }
    }
    private static boolean isS3ProtocolEnabledSafe(ConfigManager cm) {
        try { return cm.isS3ProtocolEnabled(); } catch (Exception e) { return true; }
    }

    private static void startSnapshotScheduler() {
        snapshotScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SnapshotScheduler");
            t.setDaemon(true);
            return t;
        });

        snapshotScheduler.scheduleAtFixedRate(() -> {
            try {
                if (backupManager != null && documentServer != null) {
                    createSnapshot();
                }
            } catch (Exception e) {
                logger.error("创建快照失败", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private static void startCleanupScheduler() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CleanupScheduler");
            t.setDaemon(true);
            return t;
        });

        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                if (authService != null) {
                    authService.cleanExpiredSessions();
                }
            } catch (Exception e) {
                logger.error("清理过期会话失败", e);
            }
        }, 30, 30, TimeUnit.MINUTES);
    }

    private static void shutdownServices() {
        if (!running) return;
        running = false;

        StartupBanner.printShutdown();

        createSnapshotBeforeShutdown();

        if (mysqlServer != null) mysqlServer.stop();
        if (mongoServer != null) mongoServer.stop();
        if (s3Server != null) s3Server.stop();

        if (adminServer != null) {
            System.out.println("  Stopping AdminServer...");
            adminServer.stop();
        }
        if (documentServer != null) {
            System.out.println("  Stopping DocumentServer...");
            documentServer.stop();
        }
        if (storageServer != null) {
            System.out.println("  Stopping StorageServer...");
            storageServer.stop();
        }
        if (snapshotScheduler != null) snapshotScheduler.shutdown();
        if (cleanupScheduler != null) cleanupScheduler.shutdown();
        if (LogManager.getInstance() != null) LogManager.getInstance().shutdown();

        StartupBanner.printShutdownComplete();
    }

    private static void savePidFile(String pid) {
        try (FileOutputStream fos = new FileOutputStream(PID_FILE)) {
            fos.write(pid.getBytes());
        } catch (IOException e) {
            logger.warn("保存PID文件失败: {}", e.getMessage());
        }
    }

    private static String getCurrentPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            return name.split("@")[0];
        } catch (Exception e) {
            return String.valueOf(Thread.currentThread().getId());
        }
    }

    private static void createSnapshot() {
        try {
            Path snapshotPath = Paths.get(SNAPSHOT_DIR);
            if (!Files.exists(snapshotPath)) {
                Files.createDirectories(snapshotPath);
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            Path snapshotFile = snapshotPath.resolve("snapshot_" + timestamp + ".json");

            backupManager.createSnapshot(snapshotFile.toString());
            cleanOldSnapshots(10);
        } catch (Exception e) {
            logger.error("创建快照失败", e);
        }
    }

    private static void createSnapshotBeforeShutdown() {
        try {
            Path snapshotPath = Paths.get(SNAPSHOT_DIR);
            if (!Files.exists(snapshotPath)) {
                Files.createDirectories(snapshotPath);
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            Path snapshotFile = snapshotPath.resolve("final_" + timestamp + ".json");

            backupManager.createSnapshot(snapshotFile.toString());
            logger.info("关闭前快照已创建");
        } catch (Exception e) {
            logger.error("创建关闭快照失败", e);
        }
    }

    private static void restoreFromSnapshotIfNeeded() {
        try {
            Path snapshotPath = Paths.get(SNAPSHOT_DIR);
            if (!Files.exists(snapshotPath)) return;

            Path[] snapshots = Files.list(snapshotPath)
                    .filter(p -> p.getFileName().toString().startsWith("final_"))
                    .sorted((a, b) -> b.compareTo(a))
                    .toArray(Path[]::new);

            if (snapshots.length > 0) {
                backupManager.restoreSnapshot(snapshots[0].toString());
                Files.delete(snapshots[0]);
                logger.info("从快照恢复完成");
            }
        } catch (Exception e) {
            logger.error("从快照恢复失败", e);
        }
    }

    private static void cleanOldSnapshots(int keepCount) {
        try {
            Path snapshotPath = Paths.get(SNAPSHOT_DIR);
            if (!Files.exists(snapshotPath)) return;

            Path[] snapshots = Files.list(snapshotPath)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> b.compareTo(a))
                    .toArray(Path[]::new);

            for (int i = keepCount; i < snapshots.length; i++) {
                Files.delete(snapshots[i]);
            }
        } catch (Exception e) {
            logger.error("清理旧快照失败", e);
        }
    }

    private static void printStartupInfo() {
        System.out.println();
        System.out.println("\u001B[33m  Server Information:\u001B[0m");
        System.out.println("  \u001B[90m─────────────────────────────────────────────────────────\u001B[0m");
        System.out.printf("  \u001B[36m%-16s\u001B[0m : %s%n", "Database", configManager.getDatabaseName());
        System.out.printf("  \u001B[36m%-16s\u001B[0m : http://localhost:%d%n", "Document API", configManager.getDocumentDbPort());
        System.out.printf("  \u001B[36m%-16s\u001B[0m : http://localhost:%d%n", "Storage API", configManager.getFileStoragePort());
        System.out.printf("  \u001B[36m%-16s\u001B[0m : mysql://root:ZM135790..@localhost:%d%n", "MySQL Protocol", configManager.getMysqlPort());
        System.out.printf("  \u001B[36m%-16s\u001B[0m : mongodb://root:ZM135790..@localhost:%d%n", "MongoDB Protocol", configManager.getMongodbPort());
        System.out.printf("  \u001B[36m%-16s\u001B[0m : http://localhost:%d (s3v4)%n", "S3/MinIO Protocol", configManager.getS3Port());
        System.out.printf("  \u001B[36m%-16s\u001B[0m : http://localhost:%d%n", "Admin Panel", configManager.getFileStoragePort() + 1);
        System.out.println("  \u001B[90m─────────────────────────────────────────────────────────\u001B[0m");
        System.out.printf("  \u001B[32m%-16s\u001B[0m : %s%n", "Version", FULL_VERSION + " (Build " + BUILD_VERSION + ")");
        System.out.println("  \u001B[90m─────────────────────────────────────────────────────────\u001B[0m");

        if (backgroundMode) {
            System.out.println();
            System.out.println("\u001B[33m  Running in background mode - commands disabled\u001B[0m");
        } else {
            StartupBanner.printCommandHelp();
        }
    }

    private static void startConsoleLoop() {
        if (backgroundMode) {
            try {
                Object lock = new Object();
                synchronized (lock) {
                    lock.wait();
                }
            } catch (InterruptedException e) {
                shutdownServices();
            }
            return;
        }

        Scanner scanner = new Scanner(System.in);

        while (running && scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) continue;

            switch (line.toLowerCase()) {
                case "exit":
                case "quit":
                case "q":
                    running = false;
                    break;
                case "status":
                    printStatus();
                    break;
                case "stats":
                    printStats();
                    break;
                case "logs":
                    printRecentLogs();
                    break;
                case "clear":
                case "cls":
                    clearConsole();
                    break;
                case "help":
                case "?":
                    StartupBanner.printCommandHelp();
                    break;
                default:
                    System.out.println("\u001B[33m  Unknown command: " + line + "\u001B[0m");
                    System.out.println("  Type 'help' for available commands.");
            }
        }

        scanner.close();
        shutdownServices();
    }

    private static void printStatus() {
        System.out.println();
        System.out.println("\u001B[33m  Server Status:\u001B[0m");
        System.out.println("  \u001B[90m─────────────────────────────────────\u001B[0m");

        String docStatus = documentServer != null && documentServer.isRunning() ? "\u001B[32mRunning\u001B[0m" : "\u001B[31mStopped\u001B[0m";
        String storageStatus = storageServer != null && storageServer.isRunning() ? "\u001B[32mRunning\u001B[0m" : "\u001B[31mStopped\u001B[0m";
        String adminStatus = adminServer != null && adminServer.isRunning() ? "\u001B[32mRunning\u001B[0m" : "\u001B[31mStopped\u001B[0m";
        String mysqlStatus = mysqlServer != null && mysqlServer.isRunning() ? "\u001B[32mRunning\u001B[0m" : "\u001B[33mDisabled\u001B[0m";
        String mongoStatus = mongoServer != null && mongoServer.isRunning() ? "\u001B[32mRunning\u001B[0m" : "\u001B[33mDisabled\u001B[0m";
        String s3Status = s3Server != null && s3Server.isRunning() ? "\u001B[32mRunning\u001B[0m" : "\u001B[33mDisabled\u001B[0m";

        System.out.printf("  %-16s : %s%n", "DocumentServer", docStatus);
        System.out.printf("  %-16s : %s%n", "StorageServer", storageStatus);
        System.out.printf("  %-16s : %s%n", "AdminServer", adminStatus);
        System.out.printf("  %-16s : %s%n", "MySQL-Protocol", mysqlStatus);
        System.out.printf("  %-16s : %s%n", "MongoDB-Wire", mongoStatus);
        System.out.printf("  %-16s : %s%n", "S3/MinIO", s3Status);
        System.out.printf("  %-16s : %s%n", "PID", getCurrentPid());
        System.out.printf("  %-16s : %s%n", "Database", configManager.getDatabaseName());
        System.out.println("  \u001B[90m─────────────────────────────────────\u001B[0m");
        System.out.println();
    }

    private static void printStats() {
        System.out.println();
        System.out.println("\u001B[33m  System Statistics:\u001B[0m");
        System.out.println("  \u001B[90m─────────────────────────────────────\u001B[0m");

        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory() / 1024 / 1024;
        long totalMem = runtime.totalMemory() / 1024 / 1024;
        long freeMem = runtime.freeMemory() / 1024 / 1024;
        long usedMem = totalMem - freeMem;

        System.out.printf("  %-16s : %d / %d MB%n", "Memory", usedMem, maxMem);
        System.out.printf("  %-16s : %d%n", "Processors", runtime.availableProcessors());
        System.out.printf("  %-16s : %d%n", "Threads", Thread.activeCount());

        if (LogManager.getInstance() != null) {
            Map<String, Object> logStats = LogManager.getInstance().getLogStats();
            System.out.printf("  %-16s : %s%n", "Log Archives", logStats.get("archiveCount"));
            System.out.printf("  %-16s : %s%n", "Archive Size", logStats.get("archiveSize"));
        }

        System.out.println("  \u001B[90m─────────────────────────────────────\u001B[0m");
        System.out.println();
    }

    private static void printRecentLogs() {
        System.out.println();
        System.out.println("\u001B[33m  Recent Logs:\u001B[0m");
        System.out.println("  \u001B[90m─────────────────────────────────────\u001B[0m");

        if (LogManager.getInstance() != null) {
            List<LogManager.LogEntry> logs = LogManager.getInstance().getRecentLogs(20, null);
            if (logs.isEmpty()) {
                System.out.println("  No logs available.");
            } else {
                for (LogManager.LogEntry entry : logs) {
                    String levelColor = getLevelColor(entry.getLevel());
                    System.out.printf("  %s[%s]\u001B[0m [%s] %s%n",
                            levelColor, entry.getLevel(), entry.getComponent(), entry.getMessage());
                }
            }
        } else {
            System.out.println("  Log manager not initialized.");
        }

        System.out.println("  \u001B[90m─────────────────────────────────────\u001B[0m");
        System.out.println();
    }

    private static String getLevelColor(String level) {
        switch (level) {
            case "ERROR": return "\u001B[31m";
            case "WARN": return "\u001B[33m";
            case "DEBUG": return "\u001B[90m";
            default: return "\u001B[32m";
        }
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            for (int i = 0; i < 50; i++) {
                System.out.println();
            }
        }
    }

    public static ConfigManager getConfigManager() { return configManager; }
    public static AuthService getAuthService() { return authService; }
    public static DocumentServer getDocumentServer() { return documentServer; }
    public static StorageServer getStorageServer() { return storageServer; }
    public static BackupManager getBackupManager() { return backupManager; }
    public static boolean isBackgroundMode() { return backgroundMode; }

    public static void stopAll() { shutdownServices(); }
}
