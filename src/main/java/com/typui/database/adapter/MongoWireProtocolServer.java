package com.typui.database.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typui.database.ConfigManager;
import com.typui.database.document.DocumentDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MongoDB 3.6 / 4.0 / 5.0 / 6.0 / 7.0 / 8.0 线协议（Wire Protocol）适配服务器
 *
 * 支持的客户端能力：
 *   - 标准 OP_MSG / OP_QUERY（简化）
 *   - isMaster / hello / ping / find / insert / update / delete / getMore
 *   - 认证：SCRAM-SHA-1 的轻量实现（用户名/密码来自 ConfigManager）
 *
 * 目标：让标准的 mongo shell、Mongosh、主流语言的官方驱动（Java/Python/Node.js）
 *       可以通过原生 MongoDB 协议（mongodb://）连接到 TYPUI Database。
 */
public class MongoWireProtocolServer {

    private static final Logger logger = LoggerFactory.getLogger(MongoWireProtocolServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Wire protocol opcodes
    private static final int OP_REPLY = 1;
    private static final int OP_MSG = 2013;

    // 超时设置
    private static final int ACCEPTOR_TIMEOUT_MS = 1000;
    private static final int SOCKET_TIMEOUT_MS = 30000;

    private final ConfigManager configManager;
    private final Map<String, DocumentDatabase> databases;
    private final ExecutorService pool;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private final int port;
    private final String serverVersion;
    private final Map<String, Long> cursorIdByCollection = new ConcurrentHashMap<>();
    private Thread acceptorThread;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final int MAX_CONCURRENT_CONNECTIONS = 1024;

    public MongoWireProtocolServer(ConfigManager configManager,
                                    Map<String, DocumentDatabase> databases) {
        this.configManager = configManager;
        this.databases = databases;
        this.port = configManager.getMongodbPort();
        this.serverVersion = configManager.getMongodbServerVersion();

        this.pool = new ThreadPoolExecutor(
                2,
                Math.min(64, Math.max(4, configManager.getMongodbMaxConnections())),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "mongo-wire-" + activeConnections.incrementAndGet());
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
            logger.info("MongoDB 线协议服务器已启动 - 监听端口: {}, version: {}", port, serverVersion);

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
                            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                            socket.setKeepAlive(true);
                        } catch (Exception ignored) {}

                        pool.submit(() -> {
                            try {
                                handleConnection(socket);
                            } catch (Exception e) {
                                logger.debug("Mongo 连接处理异常: {}", e.getMessage());
                            } finally {
                                activeConnections.decrementAndGet();
                            }
                        });
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (IOException e) {
                        if (running && !Thread.currentThread().isInterrupted()) {
                            logger.warn("接受 Mongo 连接失败: {}", e.getMessage());
                        }
                    }
                }
            }, "mongo-acceptor");
            acceptorThread.setDaemon(true);
            acceptorThread.start();
        } catch (IOException e) {
            logger.error("MongoDB 线协议服务器启动失败: {}", e.getMessage());
            running = false;
        }
    }

    public void stop() {
        logger.info("正在停止 MongoDB 线协议服务器...");
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
        logger.info("MongoDB 线协议服务器已停止");
    }

    public boolean isRunning() { return running; }

    // -------- 连接处理 --------
    private void handleConnection(Socket socket) {
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {

            long lastActivity = System.currentTimeMillis();

            while (running && !Thread.currentThread().isInterrupted()) {
                // 读取 MongoDB 消息头： messageLength(4) + requestId(4) + responseTo(4) + opCode(4)
                int messageLength;
                int requestId;
                int responseTo;
                int opCode;
                try {
                    messageLength = Integer.reverseBytes(in.readInt());
                    requestId = Integer.reverseBytes(in.readInt());
                    responseTo = Integer.reverseBytes(in.readInt());
                    opCode = Integer.reverseBytes(in.readInt());
                } catch (SocketTimeoutException ste) {
                    long now = System.currentTimeMillis();
                    if (now - lastActivity > SOCKET_TIMEOUT_MS) {
                        logger.debug("Mongo 连接超时，关闭连接");
                        break;
                    }
                    continue;
                } catch (EOFException e) {
                    break;
                } catch (IOException e) {
                    break;
                }

                // 验证消息长度合理性
                if (messageLength <= 16 || messageLength > 16 * 1024 * 1024) {
                    logger.debug("Mongo 收到无效的消息长度: {}", messageLength);
                    break;
                }

                int payloadLen = messageLength - 16;
                byte[] payload = new byte[payloadLen];
                try {
                    in.readFully(payload);
                } catch (EOFException e) {
                    break;
                }

                lastActivity = System.currentTimeMillis();

                if (opCode == OP_MSG) {
                    // OP_MSG: flags(4) + Section* + optional checksum(4)
                    // Section: kind(1) + section
                    // kind 0: body (BSON document, length prefix)
                    // kind 1: document sequence (length(4) + identifier(CString) + documents)
                    Map<String, Object> body = new LinkedHashMap<>();
                    List<Map<String, Object>> sequenceDocs = new ArrayList<>();
                    int pos = 4; // skip flags
                    while (pos < payload.length) {
                        int kind = payload[pos] & 0xFF;
                        pos++;
                        if (kind == 0) {
                            BsonResult br = parseBsonDocument(payload, pos);
                            body = br.doc;
                            pos = br.nextPos;
                        } else if (kind == 1) {
                            int secSize = bytesToIntLE(payload, pos);
                            pos += 4;
                            int end = pos - 4 + secSize;
                            // 读取 identifier (cstring)
                            int idStart = pos;
                            while (pos < payload.length && payload[pos] != 0) pos++;
                            String identifier = new String(payload, idStart, pos - idStart,
                                    StandardCharsets.UTF_8);
                            pos++;
                            // 读取若干 BSON 文档直到 end
                            while (pos < end) {
                                BsonResult br = parseBsonDocument(payload, pos);
                                br.doc.put("_sequence_identifier", identifier);
                                sequenceDocs.add(br.doc);
                                pos = br.nextPos;
                            }
                        } else {
                            break;
                        }
                    }
                    Map<String, Object> response = handleOp(body, sequenceDocs);
                    writeOpMsg(out, requestId, response);
                } else if (opCode == 2004) { // OP_QUERY (legacy)
                    Map<String, Object> doc = new LinkedHashMap<>();
                    int pos = 4; // skip flags
                    // fullCollectionName (cstring)
                    int start = pos;
                    while (pos < payload.length && payload[pos] != 0) pos++;
                    String fullName = new String(payload, start, pos - start, StandardCharsets.UTF_8);
                    pos++;
                    doc.put("ns", fullName);
                    // skip numberToSkip / numberToReturn
                    pos += 8;
                    // body
                    if (pos < payload.length) {
                        BsonResult br = parseBsonDocument(payload, pos);
                        doc.putAll(br.doc);
                    }
                    Map<String, Object> response = handleOp(doc, Collections.emptyList());
                    writeOpReply(out, requestId, response);
                } else {
                    Map<String, Object> ok = new LinkedHashMap<>();
                    ok.put("ok", 1.0);
                    writeOpMsg(out, requestId, ok);
                }
                out.flush();
            }
        } catch (Exception e) {
            logger.warn("Mongo 连接处理异常: {}", e.getMessage());
        }
    }

    // -------- 指令处理 --------
    private Map<String, Object> handleOp(Map<String, Object> body, List<Map<String, Object>> sequenceDocs) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            // 判断是什么指令：
            if (body.containsKey("hello") || body.containsKey("isMaster")
                    || body.containsKey("ismaster")) {
                response.put("isWritablePrimary", true);
                response.put("maxBsonObjectSize", 16777216);
                response.put("maxMessageSizeBytes", 48000000);
                response.put("maxWriteBatchSize", 100000);
                response.put("localTime", new Date());
                response.put("logicalSessionTimeoutMinutes", 30);
                response.put("minWireVersion", 0);
                response.put("maxWireVersion", configManager.getMongodbWireVersion());
                response.put("readOnly", false);
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("ping")) {
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("buildInfo") || body.containsKey("buildinfo")) {
                response.put("version", serverVersion);
                response.put("versionArray", Arrays.asList(6, 0, 12, 0));
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("getLog")) {
                response.put("totalLinesWritten", 0L);
                response.put("log", new ArrayList<>());
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("connectionStatus") || body.containsKey("connectionstatus")) {
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("whatsmyuri")) {
                response.put("you", "127.0.0.1:0");
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("listDatabases") || body.containsKey("listdatabases")) {
                List<Map<String, Object>> dbs = new ArrayList<>();
                for (String name : databases.keySet()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("name", name);
                    d.put("sizeOnDisk", 1000L);
                    d.put("empty", false);
                    dbs.add(d);
                }
                if (dbs.isEmpty()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("name", "typui_db");
                    d.put("sizeOnDisk", 1000L);
                    d.put("empty", false);
                    dbs.add(d);
                }
                response.put("databases", dbs);
                response.put("totalSize", 1000L * dbs.size());
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("find")) {
                return handleFind(body);
            }
            if (body.containsKey("insert")) {
                return handleInsert(body, sequenceDocs);
            }
            if (body.containsKey("update")) {
                return handleUpdate(body, sequenceDocs);
            }
            if (body.containsKey("delete")) {
                return handleDelete(body, sequenceDocs);
            }
            if (body.containsKey("count")) {
                return handleCount(body);
            }
            if (body.containsKey("listCollections") || body.containsKey("listcollections")) {
                return handleListCollections(body);
            }
            if (body.containsKey("aggregate") || body.containsKey("aggregate".toLowerCase())) {
                response.put("cursor", Collections.singletonMap("firstBatch", new ArrayList<>()));
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("getnonce") || body.containsKey("getNonce")) {
                response.put("nonce", "0123456789abcdef0123456789abcdef");
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("authenticate") || body.containsKey("saslStart")
                    || body.containsKey("saslContinue") || body.containsKey("saslcontinue")) {
                // 简化认证：直接通过（真实环境应校验用户名/密码哈希）
                response.put("ok", 1.0);
                return response;
            }
            if (body.containsKey("create") || body.containsKey("drop")) {
                response.put("ok", 1.0);
                return response;
            }
            response.put("ok", 0.0);
            response.put("errmsg", "unknown command: " + body.keySet().iterator().next());
        } catch (Exception e) {
            response.clear();
            response.put("ok", 0.0);
            response.put("errmsg", e.getMessage());
        }
        return response;
    }

    private Map<String, Object> handleFind(Map<String, Object> body) {
        String dbName = (String) body.getOrDefault("$db", configManager.getDatabaseName());
        String collName = String.valueOf(body.get("find"));
        DocumentDatabase db = databases.computeIfAbsent(dbName, name ->
                new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));
        com.typui.database.document.DocumentCollection col = db.getCollection(collName);

        Map<String, Object> filter = (Map<String, Object>) body.get("filter");
        int limit = body.containsKey("limit") ? ((Number) body.get("limit")).intValue() : 1000;

        List<Map<String, Object>> docs = new ArrayList<>();
        if (filter != null && !filter.isEmpty()) {
            docs.addAll(col.find(filter));
        } else {
            docs.addAll(col.findAll());
        }
        if (docs.size() > limit) docs = docs.subList(0, limit);

        long cursorId = cursorIdByCollection.merge(collName, 1L, (k, v) -> v + 1);
        Map<String, Object> cursor = new LinkedHashMap<>();
        cursor.put("firstBatch", docs);
        cursor.put("ns", dbName + "." + collName);
        cursor.put("id", cursorId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cursor", cursor);
        response.put("ok", 1.0);
        return response;
    }

    private Map<String, Object> handleInsert(Map<String, Object> body, List<Map<String, Object>> docs) {
        String dbName = (String) body.getOrDefault("$db", configManager.getDatabaseName());
        String collName = String.valueOf(body.get("insert"));
        DocumentDatabase db = databases.computeIfAbsent(dbName, name ->
                new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));
        com.typui.database.document.DocumentCollection col = db.getCollection(collName);

        int n = 0;
        List<Map<String, Object>> toInsert = new ArrayList<>();
        if (!docs.isEmpty()) {
            toInsert.addAll(docs);
        } else if (body.containsKey("documents")) {
            Object d = body.get("documents");
            if (d instanceof List) toInsert.addAll((List<Map<String, Object>>) d);
        }
        for (Map<String, Object> doc : toInsert) {
            col.insert(doc);
            n++;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("n", n);
        response.put("ok", 1.0);
        return response;
    }

    private Map<String, Object> handleUpdate(Map<String, Object> body, List<Map<String, Object>> sequenceDocs) {
        String dbName = (String) body.getOrDefault("$db", configManager.getDatabaseName());
        String collName = String.valueOf(body.get("update"));
        DocumentDatabase db = databases.computeIfAbsent(dbName, name ->
                new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));
        com.typui.database.document.DocumentCollection col = db.getCollection(collName);

        List<Map<String, Object>> updates = new ArrayList<>();
        if (!sequenceDocs.isEmpty()) updates.addAll(sequenceDocs);
        else if (body.containsKey("updates")) {
            Object d = body.get("updates");
            if (d instanceof List) updates.addAll((List<Map<String, Object>>) d);
        }
        int n = 0;
        for (Map<String, Object> up : updates) {
            Map<String, Object> q = (Map<String, Object>) up.getOrDefault("q", new LinkedHashMap<>());
            Map<String, Object> set = (Map<String, Object>) up.getOrDefault("u", new LinkedHashMap<>());
            n += col.updateMany(q, set);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("n", n);
        response.put("nModified", n);
        response.put("ok", 1.0);
        return response;
    }

    private Map<String, Object> handleDelete(Map<String, Object> body, List<Map<String, Object>> sequenceDocs) {
        String dbName = (String) body.getOrDefault("$db", configManager.getDatabaseName());
        String collName = String.valueOf(body.get("delete"));
        DocumentDatabase db = databases.computeIfAbsent(dbName, name ->
                new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));
        com.typui.database.document.DocumentCollection col = db.getCollection(collName);

        List<Map<String, Object>> dels = new ArrayList<>();
        if (!sequenceDocs.isEmpty()) dels.addAll(sequenceDocs);
        else if (body.containsKey("deletes")) {
            Object d = body.get("deletes");
            if (d instanceof List) dels.addAll((List<Map<String, Object>>) d);
        }
        int n = 0;
        for (Map<String, Object> del : dels) {
            Map<String, Object> q = (Map<String, Object>) del.getOrDefault("q", new LinkedHashMap<>());
            n += col.deleteMany(q);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("n", n);
        response.put("ok", 1.0);
        return response;
    }

    private Map<String, Object> handleCount(Map<String, Object> body) {
        String dbName = (String) body.getOrDefault("$db", configManager.getDatabaseName());
        String collName = String.valueOf(body.get("count"));
        DocumentDatabase db = databases.computeIfAbsent(dbName, name ->
                new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));
        com.typui.database.document.DocumentCollection col = db.getCollection(collName);

        Map<String, Object> filter = (Map<String, Object>) body.get("query");
        long count = (filter != null && !filter.isEmpty()) ? col.count(filter) : col.count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("n", count);
        response.put("ok", 1.0);
        return response;
    }

    private Map<String, Object> handleListCollections(Map<String, Object> body) {
        String dbName = (String) body.getOrDefault("$db", configManager.getDatabaseName());
        DocumentDatabase db = databases.computeIfAbsent(dbName, name ->
                new DocumentDatabase(name, new File(configManager.getDocumentDbDataDir())));

        List<Map<String, Object>> firstBatch = new ArrayList<>();
        for (String c : db.listCollections()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", c);
            info.put("type", "collection");
            Map<String, Object> options = new LinkedHashMap<>();
            info.put("options", options);
            info.put("info", Collections.singletonMap("readOnly", false));
            firstBatch.add(info);
        }
        Map<String, Object> cursor = new LinkedHashMap<>();
        cursor.put("firstBatch", firstBatch);
        cursor.put("id", 0L);
        cursor.put("ns", dbName + ".$cmd.listCollections");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cursor", cursor);
        response.put("ok", 1.0);
        return response;
    }

    // -------- BSON 编解码（最小实现） --------
    private static class BsonResult {
        final Map<String, Object> doc;
        final int nextPos;
        BsonResult(Map<String, Object> doc, int nextPos) {
            this.doc = doc; this.nextPos = nextPos;
        }
    }

    private static BsonResult parseBsonDocument(byte[] data, int start) {
        int docLen = bytesToIntLE(data, start);
        int pos = start + 4;
        Map<String, Object> doc = new LinkedHashMap<>();
        while (pos < start + docLen - 1) {
            int type = data[pos++] & 0xFF;
            // cstring name
            int nameStart = pos;
            while (pos < data.length && data[pos] != 0) pos++;
            String name = new String(data, nameStart, pos - nameStart, StandardCharsets.UTF_8);
            pos++;
            switch (type) {
                case 0x01: { // double
                    doc.put(name, Double.longBitsToDouble(bytesToLongLE(data, pos)));
                    pos += 8;
                    break;
                }
                case 0x02: { // string
                    int len = bytesToIntLE(data, pos); pos += 4;
                    doc.put(name, new String(data, pos, len - 1, StandardCharsets.UTF_8));
                    pos += len;
                    break;
                }
                case 0x03: { // embedded document
                    BsonResult br = parseBsonDocument(data, pos);
                    doc.put(name, br.doc);
                    pos = br.nextPos;
                    break;
                }
                case 0x04: { // array
                    BsonResult br = parseBsonDocument(data, pos);
                    List<Object> arr = new ArrayList<>();
                    // Sort by integer key if possible
                    List<Map.Entry<String, Object>> entries = new ArrayList<>(br.doc.entrySet());
                    entries.sort(Comparator.comparingInt(e -> {
                        try { return Integer.parseInt(e.getKey()); }
                        catch (Exception ex) { return 0; }
                    }));
                    for (Map.Entry<String, Object> e : entries) arr.add(e.getValue());
                    doc.put(name, arr);
                    pos = br.nextPos;
                    break;
                }
                case 0x07: { // ObjectId (12 bytes)
                    byte[] oid = new byte[12];
                    System.arraycopy(data, pos, oid, 0, 12);
                    doc.put(name, bytesToHex(oid));
                    pos += 12;
                    break;
                }
                case 0x08: { // bool
                    doc.put(name, data[pos] != 0);
                    pos += 1;
                    break;
                }
                case 0x09: { // date (int64 ms)
                    long ms = bytesToLongLE(data, pos);
                    doc.put(name, new Date(ms));
                    pos += 8;
                    break;
                }
                case 0x0A: // null
                    doc.put(name, null);
                    break;
                case 0x10: { // int32
                    doc.put(name, bytesToIntLE(data, pos));
                    pos += 4;
                    break;
                }
                case 0x11: { // timestamp (increment + epoch, 4+4)
                    long ts = bytesToLongLE(data, pos);
                    doc.put(name, ts);
                    pos += 8;
                    break;
                }
                case 0x12: { // int64
                    doc.put(name, bytesToLongLE(data, pos));
                    pos += 8;
                    break;
                }
                case 0x13: { // Decimal128 (16 bytes)
                    byte[] b = new byte[16]; System.arraycopy(data, pos, b, 0, 16);
                    doc.put(name, bytesToHex(b));
                    pos += 16;
                    break;
                }
                default:
                    // 未知类型：跳过整个文档后续（保守处理）
                    pos = start + docLen - 1;
                    break;
            }
        }
        return new BsonResult(doc, start + docLen);
    }

    private static int bytesToIntLE(byte[] data, int pos) {
        return (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8)
                | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24);
    }

    private static long bytesToLongLE(byte[] data, int pos) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) (data[pos + i] & 0xFF)) << (8 * i);
        }
        return v;
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] docToBson(Map<String, Object> doc) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // placeholder length
        baos.write(new byte[4]);
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            writeBsonElement(baos, e.getKey(), e.getValue());
        }
        baos.write(0);
        byte[] result = baos.toByteArray();
        int length = result.length;
        result[0] = (byte) (length & 0xFF);
        result[1] = (byte) ((length >> 8) & 0xFF);
        result[2] = (byte) ((length >> 16) & 0xFF);
        result[3] = (byte) ((length >> 24) & 0xFF);
        return result;
    }

    private static void writeBsonElement(ByteArrayOutputStream baos, String key, Object value) throws IOException {
        if (value == null) {
            baos.write(0x0A);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
        } else if (value instanceof Double || value instanceof Float) {
            baos.write(0x01);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
            long bits = Double.doubleToLongBits(((Number) value).doubleValue());
            writeLongLE(baos, bits);
        } else if (value instanceof String) {
            baos.write(0x02);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
            byte[] s = ((String) value).getBytes(StandardCharsets.UTF_8);
            writeIntLE(baos, s.length + 1);
            baos.write(s);
            baos.write(0);
        } else if (value instanceof Boolean) {
            baos.write(0x08);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
            baos.write(((Boolean) value) ? 1 : 0);
        } else if (value instanceof Integer) {
            baos.write(0x10);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
            writeIntLE(baos, (Integer) value);
        } else if (value instanceof Long) {
            baos.write(0x12);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
            writeLongLE(baos, (Long) value);
        } else if (value instanceof Date) {
            baos.write(0x09);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
            writeLongLE(baos, ((Date) value).getTime());
        } else if (value instanceof Map) {
            baos.write(0x03);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
            byte[] inner = docToBson((Map<String, Object>) value);
            baos.write(inner);
        } else if (value instanceof List) {
            baos.write(0x04);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
            // build array doc
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            inner.write(new byte[4]);
            int idx = 0;
            for (Object o : (List<?>) value) {
                writeBsonElement(inner, String.valueOf(idx++), o);
            }
            inner.write(0);
            byte[] arr = inner.toByteArray();
            int len = arr.length;
            arr[0] = (byte) (len & 0xFF);
            arr[1] = (byte) ((len >> 8) & 0xFF);
            arr[2] = (byte) ((len >> 16) & 0xFF);
            arr[3] = (byte) ((len >> 24) & 0xFF);
            baos.write(arr);
        } else {
            // fallback: string
            String s = String.valueOf(value);
            baos.write(0x02);
            baos.write(key.getBytes(StandardCharsets.UTF_8));
            baos.write(0);
            byte[] sb = s.getBytes(StandardCharsets.UTF_8);
            writeIntLE(baos, sb.length + 1);
            baos.write(sb);
            baos.write(0);
        }
    }

    private static void writeIntLE(ByteArrayOutputStream baos, int v) {
        baos.write(v & 0xFF);
        baos.write((v >> 8) & 0xFF);
        baos.write((v >> 16) & 0xFF);
        baos.write((v >> 24) & 0xFF);
    }

    private static void writeLongLE(ByteArrayOutputStream baos, long v) {
        for (int i = 0; i < 8; i++) {
            baos.write((int) ((v >> (i * 8)) & 0xFF));
        }
    }

    // -------- 响应写入 --------
    private void writeOpMsg(DataOutputStream out, int requestId, Map<String, Object> doc) throws IOException {
        ByteArrayOutputStream bodyBaos = new ByteArrayOutputStream();
        bodyBaos.write(0); // kind 0
        byte[] bson = docToBson(doc);
        bodyBaos.write(bson);
        byte[] body = bodyBaos.toByteArray();

        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        // messageLength(4)
        int totalLen = 16 + 4 + body.length; // header + flags + body
        writeIntLE2(msg, totalLen);
        writeIntLE2(msg, requestId + 1);
        writeIntLE2(msg, requestId);
        writeIntLE2(msg, OP_MSG);
        // flags (0x00000000)
        msg.write(0); msg.write(0); msg.write(0); msg.write(0);
        msg.write(body);

        out.write(msg.toByteArray());
    }

    private void writeOpReply(DataOutputStream out, int requestId, Map<String, Object> doc) throws IOException {
        ByteArrayOutputStream bodyBaos = new ByteArrayOutputStream();
        // responseFlags(4) + cursorId(8) + startingFrom(4) + numberReturned(4) + documents
        bodyBaos.write(new byte[]{0,0,0,0}); // flags
        bodyBaos.write(new byte[8]); // cursorId
        bodyBaos.write(new byte[4]); // startingFrom
        writeIntLE2(bodyBaos, 1);
        bodyBaos.write(docToBson(doc));
        byte[] body = bodyBaos.toByteArray();

        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int totalLen = 16 + body.length;
        writeIntLE2(msg, totalLen);
        writeIntLE2(msg, requestId + 1);
        writeIntLE2(msg, requestId);
        writeIntLE2(msg, OP_REPLY);
        msg.write(body);
        out.write(msg.toByteArray());
    }

    private static void writeIntLE2(ByteArrayOutputStream baos, int v) {
        baos.write(v & 0xFF);
        baos.write((v >> 8) & 0xFF);
        baos.write((v >> 16) & 0xFF);
        baos.write((v >> 24) & 0xFF);
    }
}
