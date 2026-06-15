package com.typui.database.error;

import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    
    public static final String SUCCESS = "SUCCESS";
    
    public static final String UNKNOWN_ERROR = "ERR_0000";
    public static final String INTERNAL_ERROR = "ERR_0001";
    public static final String INVALID_REQUEST = "ERR_0002";
    public static final String INVALID_PARAMETER = "ERR_0003";
    public static final String RESOURCE_NOT_FOUND = "ERR_0004";
    public static final String OPERATION_TIMEOUT = "ERR_0005";
    public static final String SERVICE_UNAVAILABLE = "ERR_0006";
    
    public static final String DB_NOT_FOUND = "ERR_1001";
    public static final String DB_ALREADY_EXISTS = "ERR_1002";
    public static final String DB_CREATE_FAILED = "ERR_1003";
    public static final String DB_DROP_FAILED = "ERR_1004";
    public static final String DB_ACCESS_DENIED = "ERR_1005";
    
    public static final String COLLECTION_NOT_FOUND = "ERR_1101";
    public static final String COLLECTION_ALREADY_EXISTS = "ERR_1102";
    public static final String COLLECTION_CREATE_FAILED = "ERR_1103";
    public static final String COLLECTION_DROP_FAILED = "ERR_1104";
    public static final String COLLECTION_NAME_EMPTY = "ERR_1105";
    
    public static final String DOC_NOT_FOUND = "ERR_1201";
    public static final String DOC_INSERT_FAILED = "ERR_1202";
    public static final String DOC_UPDATE_FAILED = "ERR_1203";
    public static final String DOC_DELETE_FAILED = "ERR_1204";
    public static final String DOC_INVALID_FORMAT = "ERR_1205";
    public static final String DOC_ID_MISSING = "ERR_1206";
    
    public static final String QUERY_FAILED = "ERR_1301";
    public static final String QUERY_INVALID = "ERR_1302";
    public static final String QUERY_TIMEOUT = "ERR_1303";
    public static final String QUERY_TOO_LARGE = "ERR_1304";
    
    public static final String BUCKET_NOT_FOUND = "ERR_2001";
    public static final String BUCKET_ALREADY_EXISTS = "ERR_2002";
    public static final String BUCKET_CREATE_FAILED = "ERR_2003";
    public static final String BUCKET_DROP_FAILED = "ERR_2004";
    public static final String BUCKET_NAME_EMPTY = "ERR_2005";
    public static final String BUCKET_DEFAULT_DELETE = "ERR_2006";
    
    public static final String FILE_NOT_FOUND = "ERR_2101";
    public static final String FILE_UPLOAD_FAILED = "ERR_2102";
    public static final String FILE_DELETE_FAILED = "ERR_2103";
    public static final String FILE_TOO_LARGE = "ERR_2104";
    public static final String FILE_INVALID_TYPE = "ERR_2105";
    public static final String FILE_READ_FAILED = "ERR_2106";
    public static final String FILE_WRITE_FAILED = "ERR_2107";
    
    public static final String AUTH_FAILED = "ERR_3001";
    public static final String AUTH_TOKEN_EXPIRED = "ERR_3002";
    public static final String AUTH_TOKEN_INVALID = "ERR_3003";
    public static final String AUTH_PERMISSION_DENIED = "ERR_3004";
    public static final String AUTH_USER_NOT_FOUND = "ERR_3005";
    public static final String AUTH_PASSWORD_WRONG = "ERR_3006";
    public static final String AUTH_SESSION_EXPIRED = "ERR_3007";
    
    public static final String CONFIG_LOAD_FAILED = "ERR_4001";
    public static final String CONFIG_SAVE_FAILED = "ERR_4002";
    public static final String CONFIG_INVALID = "ERR_4003";
    
    public static final String BACKUP_CREATE_FAILED = "ERR_5001";
    public static final String BACKUP_RESTORE_FAILED = "ERR_5002";
    public static final String BACKUP_NOT_FOUND = "ERR_5003";
    
    private static final Map<String, String> ERROR_MESSAGES = new HashMap<>();
    
    static {
        ERROR_MESSAGES.put(SUCCESS, "操作成功");
        
        ERROR_MESSAGES.put(UNKNOWN_ERROR, "未知错误");
        ERROR_MESSAGES.put(INTERNAL_ERROR, "服务器内部错误");
        ERROR_MESSAGES.put(INVALID_REQUEST, "无效的请求");
        ERROR_MESSAGES.put(INVALID_PARAMETER, "参数错误");
        ERROR_MESSAGES.put(RESOURCE_NOT_FOUND, "资源不存在");
        ERROR_MESSAGES.put(OPERATION_TIMEOUT, "操作超时");
        ERROR_MESSAGES.put(SERVICE_UNAVAILABLE, "服务不可用");
        
        ERROR_MESSAGES.put(DB_NOT_FOUND, "数据库不存在");
        ERROR_MESSAGES.put(DB_ALREADY_EXISTS, "数据库已存在");
        ERROR_MESSAGES.put(DB_CREATE_FAILED, "数据库创建失败");
        ERROR_MESSAGES.put(DB_DROP_FAILED, "数据库删除失败");
        ERROR_MESSAGES.put(DB_ACCESS_DENIED, "数据库访问被拒绝");
        
        ERROR_MESSAGES.put(COLLECTION_NOT_FOUND, "集合不存在");
        ERROR_MESSAGES.put(COLLECTION_ALREADY_EXISTS, "集合已存在");
        ERROR_MESSAGES.put(COLLECTION_CREATE_FAILED, "集合创建失败");
        ERROR_MESSAGES.put(COLLECTION_DROP_FAILED, "集合删除失败");
        ERROR_MESSAGES.put(COLLECTION_NAME_EMPTY, "集合名称不能为空");
        
        ERROR_MESSAGES.put(DOC_NOT_FOUND, "文档不存在");
        ERROR_MESSAGES.put(DOC_INSERT_FAILED, "文档插入失败");
        ERROR_MESSAGES.put(DOC_UPDATE_FAILED, "文档更新失败");
        ERROR_MESSAGES.put(DOC_DELETE_FAILED, "文档删除失败");
        ERROR_MESSAGES.put(DOC_INVALID_FORMAT, "文档格式无效");
        ERROR_MESSAGES.put(DOC_ID_MISSING, "文档ID缺失");
        
        ERROR_MESSAGES.put(QUERY_FAILED, "查询失败");
        ERROR_MESSAGES.put(QUERY_INVALID, "查询条件无效");
        ERROR_MESSAGES.put(QUERY_TIMEOUT, "查询超时");
        ERROR_MESSAGES.put(QUERY_TOO_LARGE, "查询结果过大");
        
        ERROR_MESSAGES.put(BUCKET_NOT_FOUND, "存储桶不存在");
        ERROR_MESSAGES.put(BUCKET_ALREADY_EXISTS, "存储桶已存在");
        ERROR_MESSAGES.put(BUCKET_CREATE_FAILED, "存储桶创建失败");
        ERROR_MESSAGES.put(BUCKET_DROP_FAILED, "存储桶删除失败");
        ERROR_MESSAGES.put(BUCKET_NAME_EMPTY, "存储桶名称不能为空");
        ERROR_MESSAGES.put(BUCKET_DEFAULT_DELETE, "不能删除默认存储桶");
        
        ERROR_MESSAGES.put(FILE_NOT_FOUND, "文件不存在");
        ERROR_MESSAGES.put(FILE_UPLOAD_FAILED, "文件上传失败");
        ERROR_MESSAGES.put(FILE_DELETE_FAILED, "文件删除失败");
        ERROR_MESSAGES.put(FILE_TOO_LARGE, "文件大小超出限制");
        ERROR_MESSAGES.put(FILE_INVALID_TYPE, "文件类型不支持");
        ERROR_MESSAGES.put(FILE_READ_FAILED, "文件读取失败");
        ERROR_MESSAGES.put(FILE_WRITE_FAILED, "文件写入失败");
        
        ERROR_MESSAGES.put(AUTH_FAILED, "认证失败");
        ERROR_MESSAGES.put(AUTH_TOKEN_EXPIRED, "Token已过期");
        ERROR_MESSAGES.put(AUTH_TOKEN_INVALID, "Token无效");
        ERROR_MESSAGES.put(AUTH_PERMISSION_DENIED, "权限不足");
        ERROR_MESSAGES.put(AUTH_USER_NOT_FOUND, "用户不存在");
        ERROR_MESSAGES.put(AUTH_PASSWORD_WRONG, "密码错误");
        ERROR_MESSAGES.put(AUTH_SESSION_EXPIRED, "会话已过期");
        
        ERROR_MESSAGES.put(CONFIG_LOAD_FAILED, "配置加载失败");
        ERROR_MESSAGES.put(CONFIG_SAVE_FAILED, "配置保存失败");
        ERROR_MESSAGES.put(CONFIG_INVALID, "配置无效");
        
        ERROR_MESSAGES.put(BACKUP_CREATE_FAILED, "备份创建失败");
        ERROR_MESSAGES.put(BACKUP_RESTORE_FAILED, "备份恢复失败");
        ERROR_MESSAGES.put(BACKUP_NOT_FOUND, "备份不存在");
    }
    
    public static String getMessage(String code) {
        return ERROR_MESSAGES.getOrDefault(code, "未知错误: " + code);
    }
    
    public static Map<String, Object> createResponse(String code) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", SUCCESS.equals(code));
        response.put("code", code);
        response.put("message", getMessage(code));
        return response;
    }
    
    public static Map<String, Object> createResponse(String code, String customMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", SUCCESS.equals(code));
        response.put("code", code);
        response.put("message", customMessage);
        return response;
    }
    
    public static Map<String, Object> createResponse(String code, Map<String, Object> data) {
        Map<String, Object> response = createResponse(code);
        response.putAll(data);
        return response;
    }
    
    public static Map<String, Object> success() {
        return createResponse(SUCCESS);
    }
    
    public static Map<String, Object> success(String message) {
        return createResponse(SUCCESS, message);
    }
    
    public static Map<String, Object> success(Map<String, Object> data) {
        return createResponse(SUCCESS, data);
    }
    
    public static Map<String, Object> error(String code) {
        return createResponse(code);
    }
    
    public static Map<String, Object> error(String code, String message) {
        return createResponse(code, message);
    }
    
    public static Map<String, Object> error(String code, Throwable t) {
        Map<String, Object> response = createResponse(code);
        response.put("exception", t.getClass().getName());
        response.put("detail", t.getMessage());
        return response;
    }
}
