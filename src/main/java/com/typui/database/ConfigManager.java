package com.typui.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 配置管理器
 * 负责加载和管理数据库配置
 * 支持 MySQL 5.7-9.0 / MongoDB 3.6-8.0 / MinIO(S3) 多协议统一适配
 */
public class ConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.INDENT_OUTPUT, false);

    // 默认账户/密码（用户要求：root / ZM135790..）
    public static final String DEFAULT_ROOT_USER = "root";
    public static final String DEFAULT_ROOT_PASSWORD = "ZM135790..";

    public static final String[] MYSQL_COMPAT_VERSIONS = {
            "5.7", "8.0", "8.1", "8.2", "8.3", "8.4", "8.5", "9.0"
    };
    public static final String[] MONGODB_COMPAT_VERSIONS = {
            "3.6", "4.0", "4.2", "4.4", "5.0", "6.0", "7.0", "8.0"
    };

    private final File configFile;
    private Map<String, Object> config;

    public ConfigManager() {
        this.configFile = new File("config.json");
        loadConfig();
    }

    @SuppressWarnings("unchecked")
    private void loadConfig() {
        if (configFile.exists()) {
            try {
                config = objectMapper.readValue(configFile, Map.class);
                logger.info("配置加载成功");
                ensureDefaultSections();
            } catch (IOException e) {
                logger.error("配置加载失败: {}", e.getMessage());
                createDefaultConfig();
            }
        } else {
            createDefaultConfig();
        }
    }

    /**
     * 确保旧配置文件升级时存在所有必要 section
     */
    @SuppressWarnings("unchecked")
    private void ensureDefaultSections() {
        boolean changed = false;

        if (!config.containsKey("databaseName")) {
            config.put("databaseName", "typui_db");
            changed = true;
        }
        if (!config.containsKey("encryptionEnabled")) {
            config.put("encryptionEnabled", true);
            changed = true;
        }
        if (!config.containsKey("documentDatabase")) {
            config.put("documentDatabase", defaultDocumentDb());
            changed = true;
        }
        if (!config.containsKey("fileStorage")) {
            config.put("fileStorage", defaultFileStorage());
            changed = true;
        }
        if (!config.containsKey("auth")) {
            config.put("auth", defaultAuth());
            changed = true;
        }
        if (!config.containsKey("admin")) {
            config.put("admin", defaultAdmin());
            changed = true;
        }
        if (!config.containsKey("mysqlProtocol")) {
            config.put("mysqlProtocol", defaultMysqlProtocol());
            changed = true;
        }
        if (!config.containsKey("mongodbProtocol")) {
            config.put("mongodbProtocol", defaultMongodbProtocol());
            changed = true;
        }
        if (!config.containsKey("s3Protocol")) {
            config.put("s3Protocol", defaultS3Protocol());
            changed = true;
        }
        if (!config.containsKey("mysqlVersions")) {
            config.put("mysqlVersions", defaultMysqlVersions());
            changed = true;
        }
        if (!config.containsKey("mongodbVersions")) {
            config.put("mongodbVersions", defaultMongodbVersions());
            changed = true;
        }
        if (!config.containsKey("snapshot")) {
            config.put("snapshot", defaultSnapshot());
            changed = true;
        }

        // 强制把 admin 账户拉到 root/ZM135790..
        Map<String, Object> admin = (Map<String, Object>) config.get("admin");
        if (!DEFAULT_ROOT_USER.equals(admin.get("username"))
                || !DEFAULT_ROOT_PASSWORD.equals(admin.get("password"))) {
            admin.put("username", DEFAULT_ROOT_USER);
            admin.put("password", DEFAULT_ROOT_PASSWORD);
            changed = true;
        }

        if (changed) {
            try {
                objectMapper.writeValue(configFile, config);
                logger.info("配置已升级到最新结构");
            } catch (IOException e) {
                logger.error("写入配置失败: {}", e.getMessage());
            }
        }
    }

    private void createDefaultConfig() {
        config = new LinkedHashMap<>();

        config.put("databaseName", "typui_db");
        config.put("encryptionEnabled", true);

        config.put("documentDatabase", defaultDocumentDb());
        config.put("fileStorage", defaultFileStorage());
        config.put("auth", defaultAuth());
        config.put("admin", defaultAdmin());
        config.put("snapshot", defaultSnapshot());

        // ------ 新增: MySQL 原生协议层 ------
        config.put("mysqlProtocol", defaultMysqlProtocol());
        // ------ 新增: MongoDB 原生协议层 ------
        config.put("mongodbProtocol", defaultMongodbProtocol());
        // ------ 新增: MinIO/S3 兼容协议层 ------
        config.put("s3Protocol", defaultS3Protocol());

        // ------ MySQL 5.7 ~ 9.0 各版本的默认配置/方言/兼容标志 ------
        config.put("mysqlVersions", defaultMysqlVersions());

        // ------ MongoDB 3.6 ~ 8.0 各版本的默认配置/特性开关 ------
        config.put("mongodbVersions", defaultMongodbVersions());

        try {
            objectMapper.writeValue(configFile, config);
            logger.info("默认配置已创建 (root/ZM135790.. + MySQL/MongoDB/MinIO 协议层)");
        } catch (IOException e) {
            logger.error("创建默认配置失败: {}", e.getMessage());
        }
    }

    // ---------------- 各 section 的默认构造器 ----------------

    private Map<String, Object> defaultDocumentDb() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("port", 27016);
        m.put("host", "0.0.0.0");
        m.put("dataDir", "db");
        m.put("maxConnections", 100);
        m.put("encryptionEnabled", true);
        return m;
    }

    private Map<String, Object> defaultFileStorage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("port", 9000);
        m.put("host", "0.0.0.0");
        m.put("dataDir", "tec");
        m.put("maxFileSize", 104857600);
        m.put("encryptionEnabled", true);
        return m;
    }

    private Map<String, Object> defaultAuth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("tokenExpireHours", 24);
        m.put("secretKey", "typui-database-secret-key-2024");
        return m;
    }

    private Map<String, Object> defaultAdmin() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", DEFAULT_ROOT_USER);
        m.put("password", DEFAULT_ROOT_PASSWORD);
        m.put("role", "admin");
        return m;
    }

    private Map<String, Object> defaultSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("intervalSeconds", 60);
        m.put("maxSnapshots", 10);
        return m;
    }

    private Map<String, Object> defaultMysqlProtocol() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("host", "0.0.0.0");
        m.put("port", 3306);
        // 默认客户端使用的兼容版本（客户端可在连接时指定其它版本）
        m.put("defaultVersion", "8.0");
        m.put("serverVersion", "8.0.36-TYPUI");
        m.put("charset", "utf8mb4");
        m.put("maxConnections", 200);
        m.put("allowPublicKeyRetrieval", true);
        m.put("useSSL", false);
        m.put("defaultAuthPlugin", "mysql_native_password");
        return m;
    }

    private Map<String, Object> defaultMongodbProtocol() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("host", "0.0.0.0");
        m.put("port", 27017);
        m.put("defaultVersion", "6.0");
        m.put("serverVersion", "6.0.12-TYPUI");
        m.put("wireVersion", 21);
        m.put("maxConnections", 200);
        return m;
    }

    private Map<String, Object> defaultS3Protocol() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("host", "0.0.0.0");
        m.put("port", 9000);
        // 使用与 root 同样的账户（可在 config.json 修改）
        m.put("accessKey", DEFAULT_ROOT_USER);
        m.put("secretKey", DEFAULT_ROOT_PASSWORD);
        m.put("region", "us-east-1");
        m.put("signatureVersion", "v4");
        m.put("pathStyleAccess", true);
        return m;
    }

    private Map<String, Object> defaultMysqlVersions() {
        Map<String, Object> root = new LinkedHashMap<>();
        // 5.7
        Map<String, Object> v57 = new LinkedHashMap<>();
        v57.put("sqlMode", "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION");
        v57.put("defaultCharset", "latin1");
        v57.put("authPlugin", "mysql_native_password");
        v57.put("maxAllowedPacket", 4194304);
        v57.put("defaultStorageEngine", "InnoDB");
        v57.put("supportJson", true);
        v57.put("supportWindowFunctions", false);
        v57.put("supportCte", false);
        v57.put("supportGeneratedColumns", true);
        root.put("5.7", v57);

        // 8.0
        Map<String, Object> v80 = new LinkedHashMap<>();
        v80.put("sqlMode", "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION");
        v80.put("defaultCharset", "utf8mb4");
        v80.put("authPlugin", "caching_sha2_password");
        v80.put("maxAllowedPacket", 67108864);
        v80.put("defaultStorageEngine", "InnoDB");
        v80.put("supportJson", true);
        v80.put("supportWindowFunctions", true);
        v80.put("supportCte", true);
        v80.put("supportGeneratedColumns", true);
        v80.put("supportCommonTableExpressions", true);
        v80.put("supportInvisibleIndexes", true);
        root.put("8.0", v80);

        // 8.1 (innovation)
        Map<String, Object> v81 = new LinkedHashMap<>(v80);
        v81.put("versionComment", "Innovation release");
        v81.put("supportExplainAnalyze", true);
        root.put("8.1", v81);

        // 8.2
        Map<String, Object> v82 = new LinkedHashMap<>(v80);
        v82.put("versionComment", "Innovation release");
        root.put("8.2", v82);

        // 8.3
        Map<String, Object> v83 = new LinkedHashMap<>(v80);
        v83.put("supportTransactionSet", true);
        root.put("8.3", v83);

        // 8.4 LTS
        Map<String, Object> v84 = new LinkedHashMap<>(v80);
        v84.put("versionComment", "LTS release");
        root.put("8.4", v84);

        // 8.5 (innovation)
        Map<String, Object> v85 = new LinkedHashMap<>(v80);
        v85.put("versionComment", "Innovation release");
        root.put("8.5", v85);

        // 9.0
        Map<String, Object> v90 = new LinkedHashMap<>(v80);
        v90.put("versionComment", "MySQL 9.0");
        v90.put("supportVector", true);
        v90.put("maxAllowedPacket", 134217728);
        root.put("9.0", v90);

        return root;
    }

    private Map<String, Object> defaultMongodbVersions() {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> v36 = new LinkedHashMap<>();
        v36.put("wireVersion", 6);
        v36.put("featureCompatibilityVersion", "3.6");
        v36.put("supportChangeStreams", true);
        v36.put("supportRetryableWrites", true);
        v36.put("supportSessions", true);
        v36.put("supportTransactions", false);
        root.put("3.6", v36);

        Map<String, Object> v40 = new LinkedHashMap<>(v36);
        v40.put("wireVersion", 8);
        v40.put("featureCompatibilityVersion", "4.0");
        v40.put("supportTransactions", true);
        root.put("4.0", v40);

        Map<String, Object> v42 = new LinkedHashMap<>(v40);
        v42.put("wireVersion", 9);
        v42.put("featureCompatibilityVersion", "4.2");
        v42.put("supportDistributedTransactions", true);
        root.put("4.2", v42);

        Map<String, Object> v44 = new LinkedHashMap<>(v42);
        v44.put("wireVersion", 11);
        v44.put("featureCompatibilityVersion", "4.4");
        v44.put("supportHiddenIndex", true);
        root.put("4.4", v44);

        Map<String, Object> v50 = new LinkedHashMap<>(v44);
        v50.put("wireVersion", 13);
        v50.put("featureCompatibilityVersion", "5.0");
        v50.put("supportTimeSeries", true);
        root.put("5.0", v50);

        Map<String, Object> v60 = new LinkedHashMap<>(v50);
        v60.put("wireVersion", 17);
        v60.put("featureCompatibilityVersion", "6.0");
        v60.put("supportQueryableEncryption", true);
        v60.put("supportChangeStreamPreAndPostImages", true);
        root.put("6.0", v60);

        Map<String, Object> v70 = new LinkedHashMap<>(v60);
        v70.put("wireVersion", 21);
        v70.put("featureCompatibilityVersion", "7.0");
        v70.put("supportSearchIndexes", true);
        root.put("7.0", v70);

        Map<String, Object> v80 = new LinkedHashMap<>(v70);
        v80.put("wireVersion", 25);
        v80.put("featureCompatibilityVersion", "8.0");
        v80.put("supportVectorSearch", true);
        root.put("8.0", v80);

        return root;
    }

    // ---------------- 通用 Getter ----------------

    public Map<String, Object> getConfig() {
        return config;
    }

    public String getDatabaseName() {
        return (String) config.getOrDefault("databaseName", "typui_db");
    }

    public void setDatabaseName(String name) {
        config.put("databaseName", name);
        saveConfig();
    }

    public boolean isEncryptionEnabled() {
        Object enabled = config.get("encryptionEnabled");
        return enabled == null || Boolean.TRUE.equals(enabled);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSection(String key) {
        return (Map<String, Object>) config.get(key);
    }

    private int intOf(Map<String, Object> m, String key, int def) {
        if (m == null) return def;
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private boolean boolOf(Map<String, Object> m, String key, boolean def) {
        if (m == null) return def;
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        try {
            return Boolean.parseBoolean(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private String strOf(Map<String, Object> m, String key, String def) {
        if (m == null) return def;
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    // ---------------- DocumentDatabase ----------------
    public int getDocumentDbPort() {
        return intOf(getSection("documentDatabase"), "port", 27016);
    }
    public String getDocumentDbHost() {
        return strOf(getSection("documentDatabase"), "host", "0.0.0.0");
    }
    public String getDocumentDbDataDir() {
        return strOf(getSection("documentDatabase"), "dataDir", "db");
    }
    public int getDocumentDbMaxConnections() {
        return intOf(getSection("documentDatabase"), "maxConnections", 100);
    }
    public boolean isDocumentDbEncryptionEnabled() {
        return boolOf(getSection("documentDatabase"), "encryptionEnabled", true);
    }

    // ---------------- FileStorage ----------------
    public int getFileStoragePort() {
        return intOf(getSection("fileStorage"), "port", 9000);
    }
    public String getFileStorageHost() {
        return strOf(getSection("fileStorage"), "host", "0.0.0.0");
    }
    public String getFileStorageDataDir() {
        return strOf(getSection("fileStorage"), "dataDir", "tec");
    }
    public long getMaxFileSize() {
        Map<String, Object> m = getSection("fileStorage");
        Object v = m == null ? null : m.get("maxFileSize");
        if (v instanceof Number) return ((Number) v).longValue();
        return 104857600L;
    }
    public boolean isFileStorageEncryptionEnabled() {
        return boolOf(getSection("fileStorage"), "encryptionEnabled", true);
    }

    // ---------------- Auth ----------------
    public boolean isAuthEnabled() {
        return boolOf(getSection("auth"), "enabled", true);
    }
    public int getTokenExpireHours() {
        return intOf(getSection("auth"), "tokenExpireHours", 24);
    }
    public String getAuthSecretKey() {
        return strOf(getSection("auth"), "secretKey", "typui-database-secret-key-2024");
    }
    public String getEncryptionKey() {
        return getAuthSecretKey();
    }

    // ---------------- Admin (root/ZM135790..) ----------------
    public String getAdminUsername() {
        return strOf(getSection("admin"), "username", DEFAULT_ROOT_USER);
    }
    public String getAdminPassword() {
        return strOf(getSection("admin"), "password", DEFAULT_ROOT_PASSWORD);
    }
    public String getAdminRole() {
        return strOf(getSection("admin"), "role", "admin");
    }

    // ---------------- Snapshot ----------------
    public boolean isSnapshotEnabled() {
        return boolOf(getSection("snapshot"), "enabled", true);
    }
    public int getSnapshotIntervalSeconds() {
        return intOf(getSection("snapshot"), "intervalSeconds", 60);
    }
    public int getMaxSnapshots() {
        return intOf(getSection("snapshot"), "maxSnapshots", 10);
    }

    // ---------------- MySQL Protocol ----------------
    public boolean isMysqlProtocolEnabled() {
        return boolOf(getSection("mysqlProtocol"), "enabled", true);
    }
    public String getMysqlHost() {
        return strOf(getSection("mysqlProtocol"), "host", "0.0.0.0");
    }
    public int getMysqlPort() {
        return intOf(getSection("mysqlProtocol"), "port", 3306);
    }
    public String getMysqlDefaultVersion() {
        return strOf(getSection("mysqlProtocol"), "defaultVersion", "8.0");
    }
    public String getMysqlServerVersion() {
        return strOf(getSection("mysqlProtocol"), "serverVersion", "8.0.36-TYPUI v3.0.31");
    }
    public String getMysqlCharset() {
        return strOf(getSection("mysqlProtocol"), "charset", "utf8mb4");
    }
    public int getMysqlMaxConnections() {
        return intOf(getSection("mysqlProtocol"), "maxConnections", 200);
    }

    /**
     * 获取指定 MySQL 版本的兼容配置（供数据库中间件层读取）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMysqlVersionConfig(String version) {
        Map<String, Object> section = getSection("mysqlVersions");
        if (section == null) return Collections.emptyMap();
        Map<String, Object> v = (Map<String, Object>) section.get(version);
        return v == null ? Collections.emptyMap() : v;
    }

    // ---------------- MongoDB Protocol ----------------
    public boolean isMongodbProtocolEnabled() {
        return boolOf(getSection("mongodbProtocol"), "enabled", true);
    }
    public String getMongodbHost() {
        return strOf(getSection("mongodbProtocol"), "host", "0.0.0.0");
    }
    public int getMongodbPort() {
        return intOf(getSection("mongodbProtocol"), "port", 27017);
    }
    public String getMongodbDefaultVersion() {
        return strOf(getSection("mongodbProtocol"), "defaultVersion", "6.0");
    }
    public String getMongodbServerVersion() {
        return strOf(getSection("mongodbProtocol"), "serverVersion", "6.0.12-TYPUI");
    }
    public int getMongodbWireVersion() {
        return intOf(getSection("mongodbProtocol"), "wireVersion", 21);
    }
    public int getMongodbMaxConnections() {
        return intOf(getSection("mongodbProtocol"), "maxConnections", 200);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMongodbVersionConfig(String version) {
        Map<String, Object> section = getSection("mongodbVersions");
        if (section == null) return Collections.emptyMap();
        Map<String, Object> v = (Map<String, Object>) section.get(version);
        return v == null ? Collections.emptyMap() : v;
    }

    // ---------------- S3/MinIO Protocol ----------------
    public boolean isS3ProtocolEnabled() {
        return boolOf(getSection("s3Protocol"), "enabled", true);
    }
    public String getS3Host() {
        return strOf(getSection("s3Protocol"), "host", "0.0.0.0");
    }
    public int getS3Port() {
        return intOf(getSection("s3Protocol"), "port", 9000);
    }
    public String getS3AccessKey() {
        return strOf(getSection("s3Protocol"), "accessKey", DEFAULT_ROOT_USER);
    }
    public String getS3SecretKey() {
        return strOf(getSection("s3Protocol"), "secretKey", DEFAULT_ROOT_PASSWORD);
    }
    public String getS3Region() {
        return strOf(getSection("s3Protocol"), "region", "us-east-1");
    }
    public String getS3SignatureVersion() {
        return strOf(getSection("s3Protocol"), "signatureVersion", "v4");
    }
    public boolean isS3PathStyleAccess() {
        return boolOf(getSection("s3Protocol"), "pathStyleAccess", true);
    }

    // ---------------- 通用写回 ----------------
    public void updateConfig(Map<String, Object> newConfig) {
        config.putAll(newConfig);
        saveConfig();
    }

    private void saveConfig() {
        try {
            objectMapper.writeValue(configFile, config);
            logger.info("配置已保存");
        } catch (IOException e) {
            logger.error("保存配置失败: {}", e.getMessage());
        }
    }
}
