package com.typui.database.error;

public class DatabaseException extends RuntimeException {
    
    private final String errorCode;
    private final int httpStatus;
    
    public DatabaseException(String errorCode) {
        super(ErrorCode.getMessage(errorCode));
        this.errorCode = errorCode;
        this.httpStatus = getHttpStatus(errorCode);
    }
    
    public DatabaseException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = getHttpStatus(errorCode);
    }
    
    public DatabaseException(String errorCode, Throwable cause) {
        super(ErrorCode.getMessage(errorCode), cause);
        this.errorCode = errorCode;
        this.httpStatus = getHttpStatus(errorCode);
    }
    
    public DatabaseException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = getHttpStatus(errorCode);
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public int getHttpStatus() {
        return httpStatus;
    }
    
    private int getHttpStatus(String code) {
        if (code.startsWith("ERR_0004") || code.startsWith("ERR_1") && code.contains("NOT_FOUND")) {
            return 404;
        }
        if (code.startsWith("ERR_0003") || code.contains("INVALID") || code.contains("EMPTY")) {
            return 400;
        }
        if (code.startsWith("ERR_3")) {
            return 401;
        }
        if (code.startsWith("ERR_0006")) {
            return 503;
        }
        if (code.contains("ALREADY_EXISTS")) {
            return 409;
        }
        return 500;
    }
    
    public static DatabaseException notFound(String code) {
        return new DatabaseException(code);
    }
    
    public static DatabaseException invalidParam(String message) {
        return new DatabaseException(ErrorCode.INVALID_PARAMETER, message);
    }
    
    public static DatabaseException internal(String message, Throwable cause) {
        return new DatabaseException(ErrorCode.INTERNAL_ERROR, message, cause);
    }
    
    public static DatabaseException dbNotFound() {
        return new DatabaseException(ErrorCode.DB_NOT_FOUND);
    }
    
    public static DatabaseException collectionNotFound() {
        return new DatabaseException(ErrorCode.COLLECTION_NOT_FOUND);
    }
    
    public static DatabaseException docNotFound() {
        return new DatabaseException(ErrorCode.DOC_NOT_FOUND);
    }
    
    public static DatabaseException bucketNotFound() {
        return new DatabaseException(ErrorCode.BUCKET_NOT_FOUND);
    }
    
    public static DatabaseException fileNotFound() {
        return new DatabaseException(ErrorCode.FILE_NOT_FOUND);
    }
    
    public static DatabaseException authFailed(String message) {
        return new DatabaseException(ErrorCode.AUTH_FAILED, message);
    }
}
