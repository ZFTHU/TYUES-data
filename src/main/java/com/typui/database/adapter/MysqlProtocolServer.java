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
 * 支持 MySQL 5.7 - 9.0 客户端连接
 */
public class MysqlProtocolServer {

    private static final Logger logger = LoggerFactory.getLogger(MysqlProtocolServer.class);

    private static final int MYSQL_PROTOCOL_VERSION = 10;
    private static final int AUTH_PLUGIN_DATA_LEN = 20;

    private static final int ACCEPTOR_TIMEOUT_MS = 1000;
    private static final int SOCKET_TIMEOUT_MS = 120000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10000;

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

    private static final long MYSQL_CAPABILITY =
            CLIENT_LONG_PASSWORD | CLIENT_FOUND_ROWS | CLIENT_LONG_FLAG |
            CLIENT_CONNECT_WITH_DB | CLIENT_IGNORE_SPACE | CLIENT_PROTOCOL_41 |
            CLIENT_INTERACTIVE | CLIENT_TRANSACTIONS | CLIENT_SECURE_CONNECTION |
            CLIENT_MULTI_STATEMENTS | CLIENT_MULTI_RESULTS | CLIENT_PS_MULTI_RESULTS |
            CLIENT_PLUGIN_AUTH | CLIENT_CONNECT_ATTRS | CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA |
            CLIENT_DEPRECATE_EOF;

    private final ConfigManager configManager;
    private final Map<String, DocumentDatabase> databases;
    private final ExecutorService pool;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private final int port;
    private final String serverVersion;
    private Thread acceptorThread;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final int MAX_CONCURRENT_CONNECTIONS = 1024;

    public MysqlProtocolServer(ConfigManager configManager, Map<String, DocumentDatabase> databases) {
        this.configManager = configManager;
        this.databases = databases;
        this.port = configManager.getMysqlPort();
        this.serverVersion = configManager.getMysqlServerVersion();

        this.pool = new ThreadPoolExecutor(
                2, Math.min(128, configManager.getMysqlMaxConnections()),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                r -> {
                    Thread t = new Thread(r, "mysql-protocol-" + activeConnections.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(ACCEPTOR_TIMEOUT_MS);
            running = true;
            logger.info("MySQL 协议服务器已启动 - 监听端口: {}, version: {}", port, serverVersion);

            acceptorThread = new Thread(() -> {
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        Socket socket = serverSocket.accept();
                        if (socket == null) continue;

                        if (activeConnections.get() > MAX_CONCURRENT_CONNECTIONS) {
                            try {
                                socket.close();
                            } catch (IOException ignored) {}
                            continue;
                        }

                        try {
                            socket.setTcpNoDelay(true);
                            socket.setKeepAlive(true);
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
                        continue;
                    } catch (IOException e) {
                        if (running && !Thread.currentThread().isInterrupted()) {
                            logger.warn("接受 MySQL 连接失败: {}", e.getMessage());
                        }
                    }
                }
            }, "mysql-acceptor");
            acceptorThread.setDaemon(true);
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

    private void handleConnection(Socket socket) throws Exception {
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            byte[] salt = new byte[AUTH_PLUGIN_DATA_LEN];
            new SecureRandom().nextBytes(salt);

            sendHandshake(out, salt);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            String currentDb = "";
            boolean authenticated = false;
            long lastActivity = System.currentTimeMillis();

            while (running && !Thread.currentThread().isInterrupted()) {
                int length;
                int clientSeq;
                try {
                    int b1 = in.read();
                    if (b1 < 0) break;
                    int b2 = in.read();
                    int b3 = in.read();
                    if (b2 < 0 || b3 < 0) break;
                    length = b1 | (b2 << 8) | (b3 << 16);
                    clientSeq = in.read();
                } catch (SocketTimeoutException ste) {
                    if (System.currentTimeMillis() - lastActivity > SOCKET_TIMEOUT_MS) {
                        break;
                    }
                    continue;
                } catch (EOFException e) {
                    break;
                } catch (IOException e) {
                    break;
                }

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

                logger.info("MySQL received: clientSeq={}, length={}", clientSeq, length);
                int responseSeq = (clientSeq + 1) & 0xFF;

                int command = payload[0] & 0xFF;
                switch (command) {
                    case 0x01: // COM_QUIT
                        sendOK(out, responseSeq);
                        return;

                    case 0x02: // COM_INIT_DB
                        currentDb = new String(payload, 1, length - 1, StandardCharsets.UTF_8);
                        databases.computeIfAbsent(currentDb, name ->
                                new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));
                        sendOK(out, responseSeq);
                        break;

                    case 0x03: { // COM_QUERY
                        String sql = new String(payload, 1, length - 1, StandardCharsets.UTF_8).trim();
                        logger.info("MySQL SQL: {}", sql);
                        String lower = sql.toLowerCase(Locale.ROOT).trim();
                        logger.info("MySQL SQL lower: '{}'", lower);
                        boolean isSelectShow = lower.startsWith("select") || lower.startsWith("show");
                        logger.info("MySQL isSelectShow: {}", isSelectShow);
                        try {
                            if (isSelectShow) {
                                logger.info("MySQL sending result set for SELECT/SHOW");
                                List<Map<String, Object>> rows = executeQuery(currentDb, sql);
                                sendResultSet(out, responseSeq, rows);
                            } else {
                                logger.info("MySQL sending OK for DML/DDL");
                                List<Map<String, Object>> rows = executeQuery(currentDb, sql);
                                long affectedRows = 0;
                                long lastInsertId = 0;
                                if (rows != null && !rows.isEmpty()) {
                                    Map<String, Object> first = rows.get(0);
                                    Object ar = first.get("affected_rows");
                                    Object lid = first.get("inserted_id");
                                    if (ar != null) {
                                        try {
                                            affectedRows = Long.parseLong(String.valueOf(ar));
                                        } catch (NumberFormatException e) { affectedRows = 0; }
                                    }
                                    if (lid != null) {
                                        try {
                                            lastInsertId = Long.parseLong(String.valueOf(lid));
                                        } catch (NumberFormatException e) { lastInsertId = 0; }
                                    }
                                }
                                logger.info("MySQL affectedRows={}, lastInsertId={}", affectedRows, lastInsertId);
                                sendOKWithAffected(out, responseSeq, affectedRows, lastInsertId);
                            }
                        } catch (Exception e) {
                            logger.error("MySQL query error: {}", e.getMessage(), e);
                            sendError(out, responseSeq, 1064, e.getMessage());
                        }
                        break;
                    }

                    case 0x0B: // COM_STATISTICS
                        sendStatistics(out, responseSeq);
                        break;

                    case 0x0E: // COM_PING
                        sendOK(out, responseSeq);
                        break;

                    case 0x00: // HandshakeResponse41 / AuthSwitchResponse
                    case 0xFE: { // AuthSwitchRequest response
                        if (!authenticated) {
                            String authPlugin = detectAuthPlugin(payload);
                            boolean authResult = verifyHandshakeResponse(payload, salt, authPlugin);

                            if (authResult) {
                                authenticated = true;
                                sendOK(out, responseSeq);
                                logger.debug("MySQL 认证成功");
                            } else {
                                logger.debug("MySQL 认证失败");
                                sendError(out, responseSeq, 1045, "Access denied");
                                return;
                            }
                        } else {
                            sendOK(out, responseSeq);
                        }
                        break;
                    }

                    case 0x1F: // COM_CHANGE_USER
                        if (!authenticated) {
                            String authPlugin = detectAuthPlugin(payload);
                            authenticated = verifyHandshakeResponse(payload, salt, authPlugin);
                            if (authenticated) {
                                sendOK(out, responseSeq);
                            } else {
                                sendError(out, responseSeq, 1045, "Access denied");
                            }
                        } else {
                            sendOK(out, responseSeq);
                        }
                        break;

                    case 0x11: // COM_RESET_CONNECTION
                        sendOK(out, responseSeq);
                        break;

                    default:
                        sendOK(out, responseSeq);
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

    private void sendHandshake(DataOutputStream out, byte[] salt) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        baos.write(MYSQL_PROTOCOL_VERSION);

        String handshakeVersion = getCompatibleVersion(serverVersion);
        baos.write(handshakeVersion.getBytes(StandardCharsets.UTF_8));
        baos.write(0);

        int tid = (int) Thread.currentThread().getId();
        baos.write(tid & 0xFF);
        baos.write((tid >> 8) & 0xFF);
        baos.write((tid >> 16) & 0xFF);
        baos.write((tid >> 24) & 0xFF);

        baos.write(salt, 0, 8);  // auth-plugin-data-part-1: 8 bytes
        baos.write(0);            // filler

        int capLower = (int) (MYSQL_CAPABILITY & 0xFFFF);
        baos.write(capLower & 0xFF);
        baos.write((capLower >> 8) & 0xFF);

        baos.write(45);  // charset: utf8

        baos.write(0x02);
        baos.write(0x00);  // status flags: AUTOCOMMIT

        int capUpper = (int) ((MYSQL_CAPABILITY >> 16) & 0xFFFF);
        baos.write(capUpper & 0xFF);
        baos.write((capUpper >> 8) & 0xFF);

        baos.write(21);  // auth-plugin-data-len

        for (int i = 0; i < 10; i++) baos.write(0);  // reserved (10 bytes)

        // auth-plugin-data-part-2: 13 bytes (12 from salt[8..20] + 1 null terminator)
        // Note: AUTH_PLUGIN_DATA_LEN is 20, so salt[8..20] = 12 bytes
        // Total salt is 8 + 12 = 20, but auth-plugin-data-len advertises 21
        // (the +1 is the null terminator for compatibility)
        baos.write(salt, 8, 12);
        baos.write(0);

        String authPlugin = "mysql_native_password";
        baos.write(authPlugin.getBytes(StandardCharsets.UTF_8));
        baos.write(0);

        writePacket(out, 0, baos.toByteArray());
        out.flush();
    }

    private String getCompatibleVersion(String configuredVersion) {
        String v = configuredVersion;
        // Strip any existing -TYPUI suffix to avoid double-suffixing
        if (v.endsWith("-TYPUI")) {
            v = v.substring(0, v.length() - "-TYPUI".length());
        }
        if (v.contains("9.0")) {
            return "8.0.36-TYPUI";
        } else if (v.contains("8.4") || v.contains("8.5")) {
            return "8.0.36-TYPUI";
        } else if (v.contains("8.0") || v.contains("8.1") ||
                   v.contains("8.2") || v.contains("8.3")) {
            return v + "-TYPUI";
        } else if (v.contains("5.7")) {
            return "5.7.44-TYPUI";
        } else {
            return "8.0.36-TYPUI";
        }
    }

    private String detectAuthPlugin(byte[] payload) {
        if (payload.length < 36) {
            return "mysql_native_password";
        }

        long capability = 0;
        for (int i = 0; i < 4 && i < payload.length; i++) {
            capability |= ((long) payload[i] & 0xFF) << (8 * i);
        }

        if ((capability & CLIENT_PLUGIN_AUTH) != 0) {
            int pos = 4 + 4 + 1 + 23;
            while (pos < payload.length && payload[pos] != 0) {
                pos++;
            }
            pos++;

            if (pos < payload.length) {
                int authLen = payload[pos++] & 0xFF;
                pos += authLen;

                if (pos < payload.length && payload[pos] != 0) {
                    while (pos < payload.length && payload[pos] != 0) pos++;
                    pos++;
                }

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

        return "mysql_native_password";
    }

    private boolean verifyHandshakeResponse(byte[] payload, byte[] salt, String authPlugin) {
        try {
            int pos = 4 + 4 + 1 + 23;

            StringBuilder userSb = new StringBuilder();
            while (pos < payload.length && payload[pos] != 0) {
                userSb.append((char) (payload[pos++] & 0xFF));
            }
            pos++;
            String username = userSb.toString();

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

            if (expectedPassword == null || expectedPassword.isEmpty()) {
                return clientHash.length == 0;
            }

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

    private boolean verifyMysqlNativePassword(byte[] clientHash, byte[] salt, String password) throws Exception {
        if (clientHash.length == 0) {
            return password == null || password.isEmpty();
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

    private boolean verifyCachingSha2Password(byte[] clientAuth, byte[] salt, String password) throws Exception {
        if (clientAuth.length == 0) {
            return true;
        }

        if (clientAuth.length != 32) {
            return verifyMysqlNativePassword(clientAuth, salt, password);
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            byte[] p1 = md.digest(password.getBytes(StandardCharsets.UTF_8));
            byte[] p2 = md.digest(p1);

            byte[] xorResult = new byte[32];
            for (int i = 0; i < 32 && i < salt.length; i++) {
                xorResult[i] = (byte) (p2[i] ^ salt[i]);
            }

            byte[] expectedHash = md.digest(xorResult);
            return Arrays.equals(expectedHash, clientAuth);
        } catch (Exception e) {
            return verifyMysqlNativePassword(clientAuth, salt, password);
        }
    }

    private void sendOK(DataOutputStream out, int seq) throws IOException {
        sendOKWithAffected(out, seq, 0, 0);
    }

    private void sendOKWithAffected(DataOutputStream out, int seq, long affectedRows, long lastInsertId) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0x00);
        writeLengthEncodedIntValue(baos, affectedRows);
        writeLengthEncodedIntValue(baos, lastInsertId);
        baos.write(0x02);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);
        byte[] data = baos.toByteArray();
        logger.info("sendOKWithAffected: seq={}, payload_len={}, payload_hex={}", seq, data.length, bytesToHex(data));
        writePacket(out, seq, data);
        out.flush();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void writeLengthEncodedIntValue(ByteArrayOutputStream baos, long value) throws IOException {
        if (value < 251) {
            baos.write((int) value);
        } else if (value < 0x10000) {
            baos.write(0xFC);
            baos.write((int) (value & 0xFF));
            baos.write((int) ((value >> 8) & 0xFF));
        } else if (value < 0x1000000) {
            baos.write(0xFD);
            baos.write((int) (value & 0xFF));
            baos.write((int) ((value >> 8) & 0xFF));
            baos.write((int) ((value >> 16) & 0xFF));
        } else {
            baos.write(0xFE);
            baos.write((int) (value & 0xFF));
            baos.write((int) ((value >> 8) & 0xFF));
            baos.write((int) ((value >> 16) & 0xFF));
            baos.write((int) ((value >> 24) & 0xFF));
            baos.write((int) ((value >> 32) & 0xFF));
            baos.write((int) ((value >> 40) & 0xFF));
            baos.write((int) ((value >> 48) & 0xFF));
            baos.write((int) ((value >> 56) & 0xFF));
        }
    }

    private void sendStatistics(DataOutputStream out, int seq) throws IOException {
        String stats = "Uptime: 3600  Threads: 1  Questions: 0  Slow queries: 0  Opens: 0  Flush tables: 1  Open tables: 0  Queries per second avg: 0.000";
        writePacket(out, seq, stats.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendEOF(DataOutputStream out, int seq) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0xFE);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x02);
        baos.write(0x00);
        writePacket(out, seq, baos.toByteArray());
        out.flush();
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
        out.flush();
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

        int currentSeq = seq;
        // 1. Column count packet
        ByteArrayOutputStream cntBaos = new ByteArrayOutputStream();
        if (colList.size() < 251) {
            cntBaos.write(colList.size());
        } else if (colList.size() < 0x10000) {
            cntBaos.write(0xFC);
            cntBaos.write(colList.size() & 0xFF);
            cntBaos.write((colList.size() >> 8) & 0xFF);
        } else {
            cntBaos.write(0xFD);
            cntBaos.write(colList.size() & 0xFF);
            cntBaos.write((colList.size() >> 8) & 0xFF);
            cntBaos.write((colList.size() >> 16) & 0xFF);
        }
        writePacket(out, currentSeq++, cntBaos.toByteArray());
        out.flush();

        // 2. Column definition packets
        for (String col : colList) {
            ByteArrayOutputStream colBaos = new ByteArrayOutputStream();
            writeLenEncString(colBaos, "def");
            writeLenEncString(colBaos, "typui");
            writeLenEncString(colBaos, "doc");
            writeLenEncString(colBaos, "doc");
            writeLenEncString(colBaos, col);
            writeLenEncString(colBaos, col);
            colBaos.write(0x0C); // filler
            colBaos.write(0x00); colBaos.write(0x00);
            colBaos.write(0x10); colBaos.write(0x00); colBaos.write(0x00); colBaos.write(0x00);
            colBaos.write(0xFD); // length
            colBaos.write(0x00); colBaos.write(0x80);
            colBaos.write(0x00);
            colBaos.write(0x00); colBaos.write(0x00);
            writePacket(out, currentSeq++, colBaos.toByteArray());
            out.flush();
        }

        // 3. EOF packet (separator)
        sendEOF(out, currentSeq++);

        // 4. Row packets
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
            writePacket(out, currentSeq++, rowBaos.toByteArray());
            out.flush();
        }

        // 5. Final EOF packet
        sendEOF(out, currentSeq);
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
    }

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
        if (lower.startsWith("create database")) {
            Pattern p = Pattern.compile("(?i)create\\s+database\\s+(?:if\\s+not\\s+exists\\s+)?([`'\"]?\\w+[`'\"]?)", Pattern.DOTALL);
            Matcher m = p.matcher(sql.trim());
            if (m.find()) {
                String dbName = stripQuotes(m.group(1));
                databases.computeIfAbsent(dbName, name ->
                        new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));
                List<Map<String, Object>> res = new ArrayList<>();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("info", "Database created");
                res.add(row);
                return res;
            }
        }
        if (lower.startsWith("drop database")) {
            Pattern p = Pattern.compile("(?i)drop\\s+database\\s+(?:if\\s+exists\\s+)?([`'\"]?\\w+[`'\"]?)", Pattern.DOTALL);
            Matcher m = p.matcher(sql.trim());
            if (m.find()) {
                String dbName = stripQuotes(m.group(1));
                databases.remove(dbName);
                List<Map<String, Object>> res = new ArrayList<>();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("info", "Database dropped");
                res.add(row);
                return res;
            }
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
        String trimmedLower = sql.trim().toLowerCase(Locale.ROOT);

        if (trimmedLower.equals("select 1") || trimmedLower.startsWith("select 1;")) {
            List<Map<String, Object>> res = new ArrayList<>();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("1", "1");
            res.add(row);
            return res;
        }

        Pattern p = Pattern.compile(
                "(?i)^select\\s+(.+?)\\s+from\\s+([`'\"]?\\w+[`'\"]?)" +
                        "(?:\\s+where\\s+(.+?))?" +
                        "(?:\\s+order\\s+by\\s+(.+?))?" +
                        "(?:\\s+limit\\s+(\\d+)(?:\\s+(?:offset|,)\\s+(\\d+))?)?" +
                        "\\s*;?$",
                Pattern.DOTALL);
        Matcher m = p.matcher(sql.trim());
        if (!m.find()) {
            return Collections.emptyList();
        }
        String columnsStr = m.group(1).trim();
        String collectionName = stripQuotes(m.group(2));
        String whereClause = m.group(3);
        String orderByClause = m.group(4);
        String limitStr = m.group(5);
        String offsetStr = m.group(6);
        int limit = limitStr == null ? 1000 : Integer.parseInt(limitStr);
        int offset = offsetStr == null ? 0 : Integer.parseInt(offsetStr);

        boolean isCount = false;
        String countAlias = "COUNT(*)";
        if (columnsStr.toLowerCase(Locale.ROOT).contains("count(")) {
            isCount = true;
            Pattern countP = Pattern.compile("(?i)count\\s*\\(\\s*\\*\\s*\\)\\s*(?:as\\s+([\\w]+))?", Pattern.DOTALL);
            Matcher countM = countP.matcher(columnsStr);
            if (countM.find()) {
                if (countM.group(1) != null) {
                    countAlias = countM.group(1);
                }
            }
        }

        List<Map<String, Object>> allDocs = new ArrayList<>();
        com.typui.database.document.DocumentCollection col =
                getOrCreateDb(currentDb).getCollection(collectionName);
        allDocs.addAll(col.findAll());

        List<Map<String, Object>> filtered = new ArrayList<>();
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            List<Condition> conditions = parseWhereConditions(whereClause);
            for (Map<String, Object> doc : allDocs) {
                if (matchConditions(doc, conditions)) {
                    filtered.add(doc);
                }
            }
        } else {
            filtered.addAll(allDocs);
        }

        if (orderByClause != null && !orderByClause.trim().isEmpty()) {
            final String obCol;
            final boolean asc;
            String ob = orderByClause.trim();
            int spIdx = ob.indexOf(' ');
            if (spIdx > 0) {
                obCol = stripQuotes(ob.substring(0, spIdx).trim());
                String dir = ob.substring(spIdx).trim().toLowerCase(Locale.ROOT);
                asc = !dir.startsWith("desc");
            } else {
                obCol = stripQuotes(ob);
                asc = true;
            }
            filtered.sort((a, b) -> {
                String va = String.valueOf(a.getOrDefault(obCol, ""));
                String vb = String.valueOf(b.getOrDefault(obCol, ""));
                try {
                    double da = Double.parseDouble(va);
                    double db = Double.parseDouble(vb);
                    return asc ? Double.compare(da, db) : Double.compare(db, da);
                } catch (NumberFormatException e) {
                    return asc ? va.compareTo(vb) : vb.compareTo(va);
                }
            });
        }

        List<Map<String, Object>> resultRows = new ArrayList<>();
        if (isCount) {
            Map<String, Object> countRow = new LinkedHashMap<>();
            countRow.put(countAlias, String.valueOf(filtered.size()));
            resultRows.add(countRow);
            return resultRows;
        }

        int start = Math.min(offset, filtered.size());
        int end = Math.min(start + limit, filtered.size());
        for (int i = start; i < end; i++) {
            resultRows.add(filtered.get(i));
        }

        if (columnsStr.trim().equals("*")) {
            return resultRows;
        }

        String[] cols = columnsStr.split(",");
        List<String> colNames = new ArrayList<>();
        for (String c : cols) {
            String colName = c.trim();
            int asIdx = colName.toLowerCase(Locale.ROOT).indexOf(" as ");
            if (asIdx > 0) {
                colName = colName.substring(0, asIdx).trim();
            }
            colNames.add(stripQuotes(colName));
        }

        List<Map<String, Object>> projected = new ArrayList<>();
        for (Map<String, Object> row : resultRows) {
            Map<String, Object> newRow = new LinkedHashMap<>();
            for (String column : colNames) {
                newRow.put(column, row.get(column));
            }
            projected.add(newRow);
        }
        return projected;
    }

    private static class Condition {
        String column;
        String operator;
        String value;

        Condition(String column, String operator, String value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }
    }

    private static List<Condition> parseWhereConditions(String clause) {
        List<Condition> conditions = new ArrayList<>();
        String[] parts = clause.split("\\s+(?i)and\\s+");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            String[] ops = {">=", "<=", "!=", "<>", ">", "<", " LIKE ", " like ", "="};
            boolean found = false;
            for (String op : ops) {
                int idx = part.toLowerCase(Locale.ROOT).indexOf(op.toLowerCase(Locale.ROOT));
                if (idx > 0) {
                    String col = stripQuotes(part.substring(0, idx).trim());
                    String val = stripSurroundingQuotes(part.substring(idx + op.length()).trim());
                    conditions.add(new Condition(col, op.trim().toUpperCase(Locale.ROOT), val));
                    found = true;
                    break;
                }
            }
        }
        return conditions;
    }

    private static boolean matchConditions(Map<String, Object> doc, List<Condition> conditions) {
        for (Condition c : conditions) {
            String valStr = String.valueOf(doc.getOrDefault(c.column, ""));
            String cmpVal = c.value;
            try {
                double dv = Double.parseDouble(valStr);
                double dc = Double.parseDouble(cmpVal);
                switch (c.operator) {
                    case "=":
                        if (dv != dc) return false;
                        break;
                    case "!=":
                    case "<>":
                        if (dv == dc) return false;
                        break;
                    case ">":
                        if (dv <= dc) return false;
                        break;
                    case "<":
                        if (dv >= dc) return false;
                        break;
                    case ">=":
                        if (dv < dc) return false;
                        break;
                    case "<=":
                        if (dv > dc) return false;
                        break;
                    case "LIKE":
                        String pattern = cmpVal.replace("%", ".*").replace("_", ".");
                        if (!valStr.matches("(?i)" + pattern)) return false;
                        break;
                    default:
                        if (!valStr.equals(cmpVal)) return false;
                }
            } catch (NumberFormatException e) {
                switch (c.operator) {
                    case "=":
                        if (!valStr.equals(cmpVal)) return false;
                        break;
                    case "!=":
                    case "<>":
                        if (valStr.equals(cmpVal)) return false;
                        break;
                    case "LIKE":
                        String pattern = cmpVal.replace("%", ".*").replace("_", ".");
                        if (!valStr.matches("(?i)" + pattern)) return false;
                        break;
                    case ">":
                        if (valStr.compareTo(cmpVal) <= 0) return false;
                        break;
                    case "<":
                        if (valStr.compareTo(cmpVal) >= 0) return false;
                        break;
                    case ">=":
                        if (valStr.compareTo(cmpVal) < 0) return false;
                        break;
                    case "<=":
                        if (valStr.compareTo(cmpVal) > 0) return false;
                        break;
                    default:
                        if (!valStr.equals(cmpVal)) return false;
                }
            }
        }
        return true;
    }

    private List<Map<String, Object>> execInsert(String currentDb, String sql) {
        Pattern p = Pattern.compile(
                "(?i)^insert\\s+into\\s+([`'\"]?\\w+[`'\"]?)\\s*(?:\\(([^)]+)\\))?\\s*values?\\s*(.+?)\\s*;?$",
                Pattern.DOTALL);
        Matcher m = p.matcher(sql.trim());
        if (!m.find()) return Collections.emptyList();
        String collectionName = stripQuotes(m.group(1));
        String[] keys = m.group(2) == null ? new String[0] :
                stripQuotesArr(m.group(2).split(","));
        String valuesPart = m.group(3);

        List<String[]> valueGroups = splitValueGroups(valuesPart);

        com.typui.database.document.DocumentCollection col =
                getOrCreateDb(currentDb).getCollection(collectionName);

        int inserted = 0;
        Object lastId = null;
        for (String[] vals : valueGroups) {
            Map<String, Object> doc = new LinkedHashMap<>();
            for (int i = 0; i < Math.max(keys.length, vals.length); i++) {
                String k = (i < keys.length) ? keys[i].trim() : "col_" + i;
                String v = (i < vals.length) ? vals[i].trim() : "";
                v = stripSurroundingQuotes(v);
                doc.put(k, v);
            }
            Map<String, Object> result = col.insert(doc);
            lastId = result.get("_id");
            inserted++;
        }

        List<Map<String, Object>> res = new ArrayList<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("inserted_id", lastId);
        info.put("affected_rows", inserted);
        res.add(info);
        return res;
    }

    private static List<String[]> splitValueGroups(String s) {
        List<String[]> groups = new ArrayList<>();
        int depth = 0;
        StringBuilder currentGroup = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
                if (depth == 1) {
                    currentGroup.setLength(0);
                    continue;
                }
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    groups.add(splitCsvValues(currentGroup.toString()));
                    continue;
                }
            }
            if (depth > 0) {
                currentGroup.append(c);
            }
        }
        return groups;
    }

    private List<Map<String, Object>> execUpdate(String currentDb, String sql) {
        Pattern p = Pattern.compile(
                "(?i)^update\\s+([`'\"]?\\w+[`'\"]?)\\s+set\\s+(.+?)(?:\\s+where\\s+(.+?))?\\s*;?$",
                Pattern.DOTALL);
        Matcher m = p.matcher(sql.trim());
        if (!m.find()) return Collections.emptyList();
        String collectionName = stripQuotes(m.group(1));
        Map<String, String> set = parseSetClause(m.group(2));
        String whereClause = m.group(3);

        com.typui.database.document.DocumentCollection col =
                getOrCreateDb(currentDb).getCollection(collectionName);
        long modified = 0;

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            List<Condition> conditions = parseWhereConditions(whereClause);
            for (Map<String, Object> doc : col.findAll()) {
                if (matchConditions(doc, conditions)) {
                    Map<String, Object> copy = new LinkedHashMap<>(doc);
                    for (Map.Entry<String, String> s : set.entrySet()) {
                        copy.put(s.getKey(), s.getValue());
                    }
                    String id = String.valueOf(doc.get("_id"));
                    col.updateById(id, copy);
                    modified++;
                }
            }
        } else {
            for (Map<String, Object> doc : col.findAll()) {
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

    private static Map<String, String> parseSetClause(String clause) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] parts = clause.split("\\s*,\\s*");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = stripQuotes(part.substring(0, eq).trim());
            String v = stripSurroundingQuotes(part.substring(eq + 1).trim());
            out.put(k, v);
        }
        return out;
    }

    private List<Map<String, Object>> execDelete(String currentDb, String sql) {
        Pattern p = Pattern.compile(
                "(?i)^delete\\s+from\\s+([`'\"]?\\w+[`'\"]?)(?:\\s+where\\s+(.+?))?\\s*;?$",
                Pattern.DOTALL);
        Matcher m = p.matcher(sql.trim());
        if (!m.find()) return Collections.emptyList();
        String collectionName = stripQuotes(m.group(1));
        String whereClause = m.group(2);

        com.typui.database.document.DocumentCollection col =
                getOrCreateDb(currentDb).getCollection(collectionName);
        long deleted = 0;
        List<String> toDelete = new ArrayList<>();

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            List<Condition> conditions = parseWhereConditions(whereClause);
            for (Map<String, Object> doc : col.findAll()) {
                if (matchConditions(doc, conditions)) {
                    toDelete.add(String.valueOf(doc.get("_id")));
                }
            }
        } else {
            for (Map<String, Object> doc : col.findAll()) {
                toDelete.add(String.valueOf(doc.get("_id")));
            }
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
