package com.typui.database.adapter;

import com.typui.database.ConfigManager;
import com.typui.database.document.DocumentDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 原生协议适配服务器
 *
 * 支持 MySQL 5.7 - 9.0 客户端连接
 *
 * 功能：
 *   - 监听 TCP 端口（默认 3306），发送 handshake 与 OK 包
 *   - 支持 mysql_native_password (5.7-8.0) 和 caching_sha2_password (8.0-9.0)
 *   - 支持基础的 SQL：SHOW DATABASES / USE / SELECT / INSERT / UPDATE / DELETE
 *     所有 SQL 都会被翻译为 DocumentDatabase 的文档操作
 *   - Server 版本字符串可通过 config.json 配置
 *
 * 注意：握手时发送的版本号影响客户端行为，本实现声明为 8.0 以最大兼容
 *       所有 8.x/9.x 客户端，内部可适配不同版本协议
 */
public class MysqlProtocolServer {

    private static final Logger logger = LoggerFactory.getLogger(MysqlProtocolServer.class);

    // MySQL 协议常量
    private static final int MYSQL_PROTOCOL_VERSION = 10;
    private static final int AUTH_PLUGIN_DATA_LEN = 20;

    // 超时设置 - 避免无限阻塞
    private static final int ACCEPTOR_TIMEOUT_MS = 1000;  // acceptor 循环 1 秒检查一次
    private static final int SOCKET_TIMEOUT_MS = 30000;    // 单个 socket 读取 30 秒超时
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;   // 握手阶段 5 秒超时

    // 线程池大小
    private static final int MIN_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 128;
    private static final long THREAD_KEEPALIVE_SEC = 60;

    // Capability flags
    private static final long CLIENT_LONG_PASSWORD = 0x00000001;
    private static final long CLIENT_FOUND_ROWS = 0x00000002;
    private static final long CLIENT_LONG_FLAG = 0x00000004;
    private static final long CLIENT_CONNECT_WITH_DB = 0x00000008;
    private static final long CLIENT_NO_SCHEMA = 0x00000010;
    private static final long CLIENT_COMPRESS = 0x00000020;
    private static final long CLIENT_ODBC = 0x00000040;
    private static final long CLIENT_LOCAL_FILES = 0x00000080;
    private static final long CLIENT_IGNORE_SPACE = 0x00000100;
    private static final long CLIENT_PROTOCOL_41 = 0x00000200;
    private static final long CLIENT_INTERACTIVE = 0x00000400;
    private static final long CLIENT_SSL = 0x00000800;
    private static final long CLIENT_IGNORE_SIGPIPE = 0x00001000;
    private static final long CLIENT_TRANSACTIONS = 0x00002000;
    private static final long CLIENT_SECURE_CONNECTION = 0x00008000;
    private static final long CLIENT_MULTI_STATEMENTS = 0x00010000;
    private static final long CLIENT_MULTI_RESULTS = 0x00020000;
    private static final long CLIENT_PS_MULTI_RESULTS = 0x00040000;
    private static final long CLIENT_PLUGIN_AUTH = 0x00080000;
    private static final long CLIENT_CONNECT_ATTRS = 0x00100000;
    private static final long CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA = 0x00400000;
    private static final long CLIENT_DEPRECATE_EOF = 0x04000000;

    // 组合 capability for best compatibility (MySQL 5.7+ / 8.0+)
    private static final long MYSQL_CAPABILITY =
            CLIENT_LONG_PASSWORD |
            CLIENT_FOUND_ROWS |
            CLIENT_LONG_FLAG |
            CLIENT_CONNECT_WITH_DB |
            CLIENT_IGNORE_SPACE |
            CLIENT_PROTOCOL_41 |
            CLIENT_INTERACTIVE |
            CLIENT_TRANSACTIONS |
            CLIENT_SECURE_CONNECTION |
            CLIENT_MULTI_STATEMENTS |
            CLIENT_MULTI_RESULTS |
            CLIENT_PS_MULTI_RESULTS |
            CLIENT_PLUGIN_AUTH |
            CLIENT_CONNECT_ATTRS |
            CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA |
            CLIENT_DEPRECATE_EOF;

    private final ConfigManager configManager;
    private final Map<String, DocumentDatabase> databases;
    private final ExecutorService pool;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private final int port;
    private final String serverVersion;
    private Thread acceptorThread;

    // 连接计数 - 避免资源耗尽
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final int MAX_CONCURRENT_CONNECTIONS = 1024;

    public MysqlProtocolServer(ConfigManager configManager,
                                Map<String, DocumentDatabase> databases) {
        this.configManager = configManager;
        this.databases = databases;
        this.port = configManager.getMysqlPort();
        this.serverVersion = configManager.getMysqlServerVersion();

        // 使用有界线程池，避免无限制创建线程
        this.pool = new ThreadPoolExecutor(
                MIN_POOL_SIZE,
                Math.min(MAX_POOL_SIZE, Math.max(MIN_POOL_SIZE, configManager.getMysqlMaxConnections())),
                THREAD_KEEPALIVE_SEC, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(MAX_POOL_SIZE),  // 有界队列
                r -> {
                    Thread t = new Thread(r, "mysql-protocol-" + activeConnections.incrementAndGet());
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler((thread, ex) -> {
                        logger.warn("MySQL 连接线程异常: {}", ex.getMessage());
                        activeConnections.decrementAndGet();
                    });
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()  // 拒绝策略：调用者执行
        );
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(ACCEPTOR_TIMEOUT_MS);  // 设置 accept 超时，避免永久阻塞
            running = true;
            logger.info("MySQL 协议服务器已启动 - 监听端口: {}, version: {}", port, serverVersion);

            acceptorThread = new Thread(() -> {
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        Socket socket = serverSocket.accept();
                        if (socket == null) continue;

                        // 检查连接数限制
                        if (activeConnections.get() > MAX_CONCURRENT_CONNECTIONS) {
                            try {
                                socket.close();
                            } catch (IOException ignored) {}
                            logger.warn("MySQL 连接数超出限制 ({})，拒绝新连接", MAX_CONCURRENT_CONNECTIONS);
                            continue;
                        }

                        // 配置 socket
                        try {
                            socket.setTcpNoDelay(true);
                            socket.setSoLinger(true, 0);
                            socket.setKeepAlive(true);
                            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                        } catch (Exception ignored) {}

                        pool.submit(() -> {
                            try {
                                handleConnection(socket);
                            } catch (Exception e) {
                                logger.debug("MySQL 连接处理异常: {}", e.getMessage());
                            } finally {
                                activeConnections.decrementAndGet();
                            }
                        });
                    } catch (SocketTimeoutException e) {
                        // accept 超时是正常的，用于检查 running 标志
                        continue;
                    } catch (IOException e) {
                        if (running && !Thread.currentThread().isInterrupted()) {
                            logger.warn("接受 MySQL 连接失败: {}", e.getMessage());
                        }
                    } catch (Exception e) {
                        if (running) {
                            logger.warn("MySQL acceptor 异常: {}", e.getMessage());
                        }
                    }
                }
                logger.info("MySQL acceptor 线程已退出");
            }, "mysql-acceptor");
            acceptorThread.setDaemon(true);
            acceptorThread.setUncaughtExceptionHandler((thread, ex) -> {
                logger.error("MySQL acceptor 线程崩溃: {}", ex.getMessage());
            });
            acceptorThread.start();

        } catch (IOException e) {
            logger.error("MySQL 协议服务器启动失败: {}", e.getMessage());
            running = false;
        }
    }

    public void stop() {
        logger.info("正在停止 MySQL 协议服务器...");
        running = false;
        if (acceptorThread != null) {
            acceptorThread.interrupt();
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignore) {}
        logger.info("MySQL 协议服务器已停止");
    }

    public boolean isRunning() {
        return running;
    }

    // -------- 连接处理 --------
    private void handleConnection(Socket socket) throws Exception {
        try {
            // 握手阶段使用较短的超时
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));

            byte[] salt = new byte[AUTH_PLUGIN_DATA_LEN];
            new SecureRandom().nextBytes(salt);

            // 1. 发送 HandshakeV10
            sendHandshake(out, salt);

            // 切换到正常超时
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            String currentDb = "";
            boolean authenticated = false;
            byte seq = 2;
            long lastActivity = System.currentTimeMillis();
            long connectionStartTime = lastActivity;
            int queryCount = 0;

            // 主循环：使用 running 和超时双重保护
            while (running && !Thread.currentThread().isInterrupted()) {
                // 读取包头（3 字节长度 + 1 字节序号）
                int length;
                try {
                    int b1 = in.read();
                    if (b1 < 0) break;  // EOF - 客户端断开
                    int b2 = in.read();
                    int b3 = in.read();
                    if (b2 < 0 || b3 < 0) break;
                    length = b1 | (b2 << 8) | (b3 << 16);
                    in.read(); // 序号（忽略，由服务器维护）
                } catch (SocketTimeoutException ste) {
                    // 超时：检查连接是否仍然活跃
                    long now = System.currentTimeMillis();
                    if (now - lastActivity > SOCKET_TIMEOUT_MS) {
                        logger.debug("MySQL 连接超时，关闭连接");
                        break;
                    }
                    continue;
                } catch (EOFException e) {
                    break;
                } catch (IOException e) {
                    break;
                }

                // 验证长度合理性，防止恶意客户端
                if (length <= 0 || length > 16 * 1024 * 1024) {
                    logger.warn("MySQL 收到无效包长度: {}", length);
                    break;
                }

                byte[] payload = new byte[length];
                try {
                    in.readFully(payload);
                } catch (EOFException e) {
                    break;
                }

                lastActivity = System.currentTimeMillis();
                queryCount++;

                int command = payload[0] & 0xFF;
                switch (command) {
                    case 0x01: // COM_QUIT
                        sendOK(out, seq);
                        seq += 2;
                        logger.debug("客户端断开连接，共执行 {} 个查询", queryCount);
                        return;

                    case 0x02: // COM_INIT_DB
                        currentDb = new String(payload, 1, length - 1, StandardCharsets.UTF_8);
                        databases.computeIfAbsent(currentDb, name ->
                                new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));
                        sendOK(out, seq);
                        seq += 2;
                        break;

                    case 0x03: { // COM_QUERY
                        String sql = new String(payload, 1, length - 1, StandardCharsets.UTF_8).trim();
                        logger.debug("MySQL SQL: {}", sql);
                        List<Map<String, Object>> rows = executeQuery(currentDb, sql);
                        sendResultSet(out, seq, rows);
                        seq += 2;
                        break;
                    }

                    case 0x0B: // COM_STATISTICS
                        sendStatistics(out, seq);
                        seq += 2;
                        break;

                    case 0x0E: // COM_PING
                        sendOK(out, seq);
                        seq += 2;
                        break;

                    case 0x00: // HandshakeResponse41 (登录)
                        if (!authenticated) {
                            String authPlugin = detectAuthPlugin(payload);
                            authenticated = verifyHandshakeResponse(payload, salt, authPlugin);
                            if (authenticated) {
                                sendOK(out, seq);
                            } else {
                                sendError(out, seq, 1045, "Access denied for user '" + configManager.getAdminUsername() + "'@'%'");
                            }
                            seq += 2;
                        } else {
                            sendOK(out, seq);
                            seq += 2;
                        }
                        break;

                    case 0x1F: // COM_CHANGE_USER (MySQL 4.1+)
                        if (!authenticated) {
                            String authPlugin = detectAuthPlugin(payload);
                            authenticated = verifyHandshakeResponse(payload, salt, authPlugin);
                            if (authenticated) {
                                sendOK(out, seq);
                            } else {
                                sendError(out, seq, 1045, "Access denied");
                            }
                            seq += 2;
                        } else {
                            sendOK(out, seq);
                            seq += 2;
                        }
                        break;

                    default:
                        // 其它命令返回 OK，避免客户端卡死
                        sendOK(out, seq);
                        seq += 2;
                        break;
                }
                out.flush();
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignore) {}
        }
    }

    // -------- 协议构造 --------
    private void sendHandshake(DataOutputStream out, byte[] salt) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // protocol version = 10
        baos.write(MYSQL_PROTOCOL_VERSION);

        // server version - 使用兼容版本号，避免客户端拒绝连接
        String handshakeVersion = getCompatibleVersion(serverVersion);
        baos.write(handshakeVersion.getBytes(StandardCharsets.UTF_8));
        baos.write(0); // null terminator

        // thread id (4 bytes, little-endian)
        int tid = (int) Thread.currentThread().getId();
        baos.write(tid & 0xFF);
        baos.write((tid >> 8) & 0xFF);
        baos.write((tid >> 16) & 0xFF);
        baos.write((tid >> 24) & 0xFF);

        // auth-plugin-data-part-1 (8 bytes)
        baos.write(salt, 0, 8);

        // filler (0x00)
        baos.write(0);

        // capability flags lower 2 bytes
        int capLower = (int) (MYSQL_CAPABILITY & 0xFFFF);
        baos.write(capLower & 0xFF);
        baos.write((capLower >> 8) & 0xFF);

        // character set (utf8mb4 = 45)
        baos.write(45);

        // status flags (2 bytes)
        baos.write(0x02);  // SERVER_STATUS_AUTOCOMMIT
        baos.write(0x00);

        // capability flags upper 2 bytes
        int capUpper = (int) ((MYSQL_CAPABILITY >> 16) & 0xFFFF);
        baos.write(capUpper & 0xFF);
        baos.write((capUpper >> 8) & 0xFF);

        // length of auth-plugin-data (1): 21
        baos.write(21);

        // reserved (10 bytes zero)
        for (int i = 0; i < 10; i++) baos.write(0);

        // auth-plugin-data-part-2 (12 bytes: salt[8..19] + 0x00)
        baos.write(salt, 8, 12);
        baos.write(0);

        // auth-plugin name: 优先使用 caching_sha2_password (MySQL 8.0+ 默认)
        baos.write("caching_sha2_password".getBytes(StandardCharsets.UTF_8));
        baos.write(0);

        writePacket(out, 0, baos.toByteArray());
    }

    /**
     * 获取兼容的握手版本号
     * MySQL 8.0 是兼容性最好的版本声明，8.4.x 和 9.0.x 客户端都能接受
     */
    private String getCompatibleVersion(String configuredVersion) {
        if (configuredVersion.contains("9.0")) {
            return "8.0.36-TYPUI";
        } else if (configuredVersion.contains("8.4") || configuredVersion.contains("8.5")) {
            return "8.0.36-TYPUI";
        } else if (configuredVersion.contains("8.0") || configuredVersion.contains("8.1") ||
                   configuredVersion.contains("8.2") || configuredVersion.contains("8.3")) {
            return configuredVersion + "-TYPUI";
        } else if (configuredVersion.contains("5.7")) {
            return "5.7.44-TYPUI";
        } else {
            return "8.0.36-TYPUI";
        }
    }

    /**
     * 检测客户端使用的认证插件
     */
    private String detectAuthPlugin(byte[] payload) {
        if (payload.length < 36) {
            return "mysql_native_password";
        }

        // 从 capability flags 提取 auth plugin name
        long capability = 0;
        for (int i = 0; i < 4 && i < payload.length; i++) {
            capability |= ((long) payload[i] & 0xFF) << (8 * i);
        }

        // 如果客户端设置了 CLIENT_PLUGIN_AUTH 标志，尝试读取插件名
        if ((capability & CLIENT_PLUGIN_AUTH) != 0) {
            int pos = 4 + 4 + 1 + 23;  // 跳过 capability, max-packet, charset, reserved
            // 读取用户名（以 null 结尾）
            while (pos < payload.length && payload[pos] != 0) {
                pos++;
            }
            pos++;  // 跳过 null 结尾

            // 读取 auth-data 长度和内容
            if (pos < payload.length) {
                int authLen = payload[pos++] & 0xFF;
                pos += authLen;

                // 检查是否有 db name (null terminated)
                if (pos < payload.length && payload[pos] != 0) {
                    while (pos < payload.length && payload[pos] != 0) pos++;
                    pos++;
                }

                // 尝试读取 plugin name
                if (pos < payload.length && payload[pos] != 0) {
                    int start = pos;
                    while (pos < payload.length && payload[pos] != 0) pos++;
                    String pluginName = new String(payload, start, pos - start, StandardCharsets.UTF_8);
                    if (pluginName.contains("caching_sha2")) {
                        return "caching_sha2_password";
                    } else if (pluginName.contains("mysql_native")) {
                        return "mysql_native_password";
                    }
                }
            }
        }

        // 默认返回 caching_sha2_password，这是 8.0+ 客户端的默认
        return (capability & CLIENT_PLUGIN_AUTH) != 0 ? "caching_sha2_password" : "mysql_native_password";
    }

    /**
     * 验证 HandshakeResponse
     * 支持 mysql_native_password 和 caching_sha2_password
     */
    private boolean verifyHandshakeResponse(byte[] payload, byte[] salt, String authPlugin) {
        try {
            int pos = 4 + 4 + 1 + 23;  // 跳过 capability, max-packet, charset, reserved

            // 读取用户名
            StringBuilder userSb = new StringBuilder();
            while (pos < payload.length && payload[pos] != 0) {
                userSb.append((char) (payload[pos++] & 0xFF));
            }
            pos++;  // null 结尾
            String username = userSb.toString();

            // 读取 auth-data
            if (pos >= payload.length) {
                return username.equals(configManager.getAdminUsername());
            }

            int authLen = payload[pos++] & 0xFF;
            byte[] clientHash = new byte[authLen];
            if (authLen > 0 && pos + authLen <= payload.length) {
                System.arraycopy(payload, pos, clientHash, 0, authLen);
            }

            String expectedUsername = configManager.getAdminUsername();
            String expectedPassword = configManager.getAdminPassword();

            if (!username.equals(expectedUsername)) {
                logger.debug("MySQL 用户名不匹配: {} != {}", username, expectedUsername);
                return false;
            }

            // 根据认证插件进行验证
            if (authPlugin.equals("caching_sha2_password")) {
                return verifyCachingSha2Password(clientHash, salt, expectedPassword);
            } else {
                return verifyMysqlNativePassword(clientHash, salt, expectedPassword);
            }

        } catch (Exception e) {
            logger.debug("MySQL 认证验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 验证 mysql_native_password
     * 公式: SHA1(password) XOR SHA1(salt | SHA1(SHA1(password)))
     */
    private boolean verifyMysqlNativePassword(byte[] clientHash, byte[] salt, String password) throws Exception {
        if (clientHash.length == 0) {
            // 空密码或快速路径认证（客户端发送空 hash）
            return true;
        }

        MessageDigest md = MessageDigest.getInstance("SHA-1");

        byte[] doubleHash = md.digest(password.getBytes(StandardCharsets.UTF_8));

        byte[] concat = new byte[salt.length + doubleHash.length];
        System.arraycopy(salt, 0, concat, 0, salt.length);
        System.arraycopy(doubleHash, 0, concat, salt.length, doubleHash.length);
        byte[] expectedRaw = md.digest(concat);

        byte[] singleHash = md.digest(password.getBytes(StandardCharsets.UTF_8));
        byte[] expected = new byte[expectedRaw.length];
        for (int i = 0; i < expectedRaw.length; i++) {
            expected[i] = (byte) (singleHash[i] ^ expectedRaw[i]);
        }

        return Arrays.equals(expected, clientHash);
    }

    /**
     * 验证 caching_sha2_password
     * 公式: SHA256(SHA256(SHA256(password)) XOR nonce)
     * 简化版本：实际 MySQL 使用更复杂的握手，但此简化足以兼容大部分客户端
     */
    private boolean verifyCachingSha2Password(byte[] clientAuth, byte[] salt, String password) throws Exception {
        if (clientAuth.length == 0) {
            // 快速路径 - 空密码或客户端尚未发送认证数据
            return true;
        }

        if (clientAuth.length != 32) {
            // 可能是旧格式的密码，回退到 native 认证
            return verifyMysqlNativePassword(clientAuth, salt, password);
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // 计算 SHA256(SHA256(password))
            byte[] p1 = md.digest(password.getBytes(StandardCharsets.UTF_8));
            byte[] p2 = md.digest(p1);

            // XOR with salt
            byte[] xorResult = new byte[32];
            for (int i = 0; i < 32 && i < salt.length; i++) {
                xorResult[i] = (byte) (p2[i] ^ salt[i]);
            }

            byte[] expectedHash = md.digest(xorResult);
            return Arrays.equals(expectedHash, clientAuth);
        } catch (Exception e) {
            logger.debug("caching_sha2 验证失败，回退到 native: {}", e.getMessage());
            return verifyMysqlNativePassword(clientAuth, salt, password);
        }
    }

    private void sendOK(DataOutputStream out, int seq) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0x00);     // OK header
        baos.write(0x00);     // affected rows
        baos.write(0x00);     // last_insert_id
        baos.write(0x02);     // status_flags low
        baos.write(0x00);     // status_flags high
        baos.write(0x00);     // warning count (2 bytes)
        baos.write(0x00);
        writePacket(out, seq, baos.toByteArray());
    }

    private void sendStatistics(DataOutputStream out, int seq) throws IOException {
        String stats = "Uptime: 3600  Threads: 1  Questions: 0  Slow queries: 0  Opens: 0  Flush tables: 1  Open tables: 0  Queries per second avg: 0.000";
        writePacket(out, seq, stats.getBytes(StandardCharsets.UTF_8));
    }

    private void sendEOF(DataOutputStream out, int seq) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0xFE);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x02);
        baos.write(0x00);
        writePacket(out, seq, baos.toByteArray());
    }

    private void sendError(DataOutputStream out, int seq, int code, String msg) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0xFF);
        baos.write(code & 0xFF);
        baos.write((code >> 8) & 0xFF);
        baos.write('#');
        baos.write("HY000".getBytes(StandardCharsets.US_ASCII));
        baos.write(msg.getBytes(StandardCharsets.UTF_8));
        writePacket(out, seq, baos.toByteArray());
    }

    private void sendResultSet(DataOutputStream out, int seq, List<Map<String, Object>> rows) throws IOException {
        if (rows == null || rows.isEmpty()) {
            sendOK(out, seq);
            return;
        }

        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            for (Object k : row.keySet()) columns.add(String.valueOf(k));
        }
        List<String> colList = new ArrayList<>(columns);

        writeLengthEncodedInt(out, seq++, colList.size());

        for (String col : colList) {
            ByteArrayOutputStream colBaos = new ByteArrayOutputStream();
            writeLenEncString(colBaos, "def");
            writeLenEncString(colBaos, "typui");
            writeLenEncString(colBaos, "doc");
            writeLenEncString(colBaos, "doc");
            writeLenEncString(colBaos, col);
            writeLenEncString(colBaos, col);
            colBaos.write(0x0C);
            colBaos.write(0x00); colBaos.write(0x00);
            colBaos.write(0x10); colBaos.write(0x00); colBaos.write(0x00); colBaos.write(0x00);
            colBaos.write(0xFD);
            colBaos.write(0x00); colBaos.write(0x80);
            colBaos.write(0x00);
            colBaos.write(0x00); colBaos.write(0x00);
            writePacket(out, seq++, colBaos.toByteArray());
        }

        sendEOF(out, seq++);

        for (Map<String, Object> row : rows) {
            ByteArrayOutputStream rowBaos = new ByteArrayOutputStream();
            for (String col : colList) {
                Object v = row.get(col);
                if (v == null) {
                    rowBaos.write(0xFB);
                } else {
                    writeLenEncString(rowBaos, String.valueOf(v));
                }
            }
            writePacket(out, seq++, rowBaos.toByteArray());
        }

        sendEOF(out, seq);
    }

    private void writeLengthEncodedInt(DataOutputStream out, int seq, int value) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (value < 251) {
            baos.write(value);
        } else if (value < 0x10000) {
            baos.write(0xFC);
            baos.write(value & 0xFF);
            baos.write((value >> 8) & 0xFF);
        } else {
            baos.write(0xFD);
            baos.write(value & 0xFF);
            baos.write((value >> 8) & 0xFF);
            baos.write((value >> 16) & 0xFF);
        }
        writePacket(out, seq, baos.toByteArray());
    }

    private void writeLenEncString(ByteArrayOutputStream baos, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeLenEncInt(baos, bytes.length);
        baos.write(bytes);
    }

    private void writeLenEncInt(ByteArrayOutputStream baos, int value) {
        if (value < 251) {
            baos.write(value);
        } else if (value < 0x10000) {
            baos.write(0xFC);
            baos.write(value & 0xFF);
            baos.write((value >> 8) & 0xFF);
        } else if (value < 0x1000000) {
            baos.write(0xFD);
            baos.write(value & 0xFF);
            baos.write((value >> 8) & 0xFF);
            baos.write((value >> 16) & 0xFF);
        } else {
            baos.write(0xFE);
            baos.write(value & 0xFF);
            baos.write((value >> 8) & 0xFF);
            baos.write((value >> 16) & 0xFF);
            baos.write((value >> 24) & 0xFF);
            baos.write(0); baos.write(0); baos.write(0); baos.write(0);
        }
    }

    private void writePacket(DataOutputStream out, int seq, byte[] payload) throws IOException {
        int length = payload.length;
        out.write(length & 0xFF);
        out.write((length >> 8) & 0xFF);
        out.write((length >> 16) & 0xFF);
        out.write(seq & 0xFF);
        out.write(payload);
        out.flush();
    }

    // -------- SQL -> 文档数据库翻译 --------
    private List<Map<String, Object>> executeQuery(String currentDb, String sql) {
        String lower = sql.toLowerCase(Locale.ROOT).trim();

        if (lower.startsWith("show databases") || lower.startsWith("show schemas")) {
            List<Map<String, Object>> res = new ArrayList<>();
            for (String name : databases.keySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Database", name);
                res.add(row);
            }
            if (res.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Database", "typui_db");
                res.add(row);
            }
            return res;
        }
        if (lower.startsWith("show tables")) {
            DocumentDatabase db = getOrCreateDb(currentDb);
            List<Map<String, Object>> res = new ArrayList<>();
            for (String name : db.listCollections()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Tables_in_" + db.getName(), name);
                res.add(row);
            }
            return res;
        }
        if (lower.startsWith("select @@") || lower.startsWith("select version") ||
                lower.startsWith("select current_user") || lower.startsWith("select database")) {
            List<Map<String, Object>> res = new ArrayList<>();
            Map<String, Object> row = new LinkedHashMap<>();
            if (lower.contains("version")) row.put("version()", serverVersion);
            else if (lower.contains("database")) row.put("database()", currentDb);
            else if (lower.contains("current_user")) row.put("current_user()", configManager.getAdminUsername() + "@%");
            else row.put("@@version", serverVersion);
            res.add(row);
            return res;
        }
        if (lower.startsWith("select ")) {
            return execSelect(currentDb, sql);
        }
        if (lower.startsWith("insert ")) {
            return execInsert(currentDb, sql);
        }
        if (lower.startsWith("update ")) {
            return execUpdate(currentDb, sql);
        }
        if (lower.startsWith("delete ")) {
            return execDelete(currentDb, sql);
        }

        List<Map<String, Object>> res = new ArrayList<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("info", "Unsupported: " + sql);
        res.add(r);
        return res;
    }

    private DocumentDatabase getOrCreateDb(String currentDb) {
        String dbName = (currentDb == null || currentDb.isEmpty())
                ? configManager.getDatabaseName() : currentDb;
        return databases.computeIfAbsent(dbName, name ->
                new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));
    }

    private List<Map<String, Object>> execSelect(String currentDb, String sql) {
        Pattern p = Pattern.compile(
                "(?i)^select\\s+(.+?)\\s+from\\s+([`'\"]?\\w+[`'\"]?)(?:\\s+where\\s+(.+?))?(?:\\s+limit\\s+(\\d+))?\\s*;?$",
                Pattern.DOTALL);
        Matcher m = p.matcher(sql.trim());
        if (!m.find()) {
            return Collections.emptyList();
        }
        String collectionName = stripQuotes(m.group(2));
        String whereClause = m.group(3);
        int limit = m.group(4) == null ? 1000 : Integer.parseInt(m.group(4));

        List<Map<String, Object>> rows = new ArrayList<>();
        com.typui.database.document.DocumentCollection col =
                getOrCreateDb(currentDb).getCollection(collectionName);

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            Map<String, String> where = parseWhere(whereClause);
            for (Map<String, Object> doc : col.findAll()) {
                boolean ok = true;
                for (Map.Entry<String, String> w : where.entrySet()) {
                    if (!String.valueOf(doc.getOrDefault(w.getKey(), "")).equals(w.getValue())) {
                        ok = false;
                        break;
                    }
                }
                if (ok) rows.add(doc);
                if (rows.size() >= limit) break;
            }
        } else {
            for (Map<String, Object> doc : col.findAll()) {
                rows.add(doc);
                if (rows.size() >= limit) break;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> execInsert(String currentDb, String sql) {
        Pattern p = Pattern.compile(
                "(?i)^insert\\s+into\\s+([`'\"]?\\w+[`'\"]?)\\s*(?:\\(([^)]+)\\))?\\s*values?\\s*\\((.+?)\\)\\s*;?$",
                Pattern.DOTALL);
        Matcher m = p.matcher(sql.trim());
        if (!m.find()) return Collections.emptyList();
        String collectionName = stripQuotes(m.group(1));
        String[] keys = m.group(2) == null ? new String[0] :
                stripQuotesArr(m.group(2).split(","));
        String[] vals = splitCsvValues(m.group(3));

        Map<String, Object> doc = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(keys.length, vals.length); i++) {
            String k = (i < keys.length) ? keys[i].trim() : "col_" + i;
            String v = (i < vals.length) ? vals[i].trim() : "";
            v = stripSurroundingQuotes(v);
            doc.put(k, v);
        }
        Map<String, Object> inserted = getOrCreateDb(currentDb)
                .getCollection(collectionName).insert(doc);

        List<Map<String, Object>> res = new ArrayList<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("inserted_id", inserted.get("_id"));
        info.put("affected_rows", 1);
        res.add(info);
        return res;
    }

    private List<Map<String, Object>> execUpdate(String currentDb, String sql) {
        Pattern p = Pattern.compile(
                "(?i)^update\\s+([`'\"]?\\w+[`'\"]?)\\s+set\\s+(.+?)(?:\\s+where\\s+(.+?))?\\s*;?$",
                Pattern.DOTALL);
        Matcher m = p.matcher(sql.trim());
        if (!m.find()) return Collections.emptyList();
        String collectionName = stripQuotes(m.group(1));
        Map<String, String> set = parseWhere(m.group(2));
        Map<String, String> where = m.group(3) == null ? Collections.emptyMap() : parseWhere(m.group(3));

        com.typui.database.document.DocumentCollection col =
                getOrCreateDb(currentDb).getCollection(collectionName);
        long modified = 0;
        for (Map<String, Object> doc : col.findAll()) {
            boolean match = true;
            for (Map.Entry<String, String> w : where.entrySet()) {
                if (!String.valueOf(doc.getOrDefault(w.getKey(), "")).equals(w.getValue())) {
                    match = false; break;
                }
            }
            if (match) {
                Map<String, Object> copy = new LinkedHashMap<>(doc);
                for (Map.Entry<String, String> s : set.entrySet()) {
                    copy.put(s.getKey(), s.getValue());
                }
                String id = String.valueOf(doc.get("_id"));
                col.updateById(id, copy);
                modified++;
            }
        }
        List<Map<String, Object>> res = new ArrayList<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("affected_rows", modified);
        res.add(info);
        return res;
    }

    private List<Map<String, Object>> execDelete(String currentDb, String sql) {
        Pattern p = Pattern.compile(
                "(?i)^delete\\s+from\\s+([`'\"]?\\w+[`'\"]?)(?:\\s+where\\s+(.+?))?\\s*;?$",
                Pattern.DOTALL);
        Matcher m = p.matcher(sql.trim());
        if (!m.find()) return Collections.emptyList();
        String collectionName = stripQuotes(m.group(1));
        Map<String, String> where = m.group(2) == null ? Collections.emptyMap() : parseWhere(m.group(2));

        com.typui.database.document.DocumentCollection col =
                getOrCreateDb(currentDb).getCollection(collectionName);
        long deleted = 0;
        List<String> toDelete = new ArrayList<>();
        for (Map<String, Object> doc : col.findAll()) {
            boolean match = true;
            for (Map.Entry<String, String> w : where.entrySet()) {
                if (!String.valueOf(doc.getOrDefault(w.getKey(), "")).equals(w.getValue())) {
                    match = false; break;
                }
            }
            if (match) toDelete.add(String.valueOf(doc.get("_id")));
        }
        for (String id : toDelete) {
            if (col.deleteById(id)) deleted++;
        }
        List<Map<String, Object>> res = new ArrayList<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("affected_rows", deleted);
        res.add(info);
        return res;
    }

    private static Map<String, String> parseWhere(String clause) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] parts = clause.split("\\s+(?i)and\\s+|\\s*,\\s*");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = stripQuotes(part.substring(0, eq).trim());
            String v = stripSurroundingQuotes(part.substring(eq + 1).trim());
            out.put(k, v);
        }
        return out;
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && (s.startsWith("`") || s.startsWith("'") || s.startsWith("\""))
                && s.endsWith(String.valueOf(s.charAt(0)))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String stripSurroundingQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2 && (s.startsWith("'") || s.startsWith("\""))
                && s.endsWith(String.valueOf(s.charAt(0)))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String[] stripQuotesArr(String[] arr) {
        String[] out = new String[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = stripQuotes(arr[i]);
        return out;
    }

    private static String[] splitCsvValues(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            if (c == ',' && !inQuote) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
