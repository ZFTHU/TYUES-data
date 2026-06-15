package com.typui.database.error;

import com.typui.database.logging.LogManager;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

public class ErrorHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ErrorHandler.class);
    
    public static void handle(Exception e, Context ctx) {
        String errorCode;
        String message;
        int httpStatus;
        
        if (e instanceof DatabaseException) {
            DatabaseException de = (DatabaseException) e;
            errorCode = de.getErrorCode();
            message = de.getMessage();
            httpStatus = de.getHttpStatus();
        } else {
            errorCode = ErrorCode.INTERNAL_ERROR;
            message = "服务器内部错误: " + e.getMessage();
            httpStatus = 500;
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", errorCode);
        response.put("message", message);
        response.put("timestamp", System.currentTimeMillis());
        
        if (httpStatus >= 500) {
            String stackTrace = getStackTrace(e);
            response.put("stackTrace", stackTrace);
            
            if (LogManager.getInstance() != null) {
                LogManager.getInstance().error("ErrorHandler", 
                    "Error [" + errorCode + "]: " + message, e);
            } else {
                logger.error("Error [{}]: {}", errorCode, message, e);
            }
        }
        
        ctx.status(httpStatus).json(response);
    }
    
    public static void handleNotFound(Context ctx) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", ErrorCode.RESOURCE_NOT_FOUND);
        response.put("message", ErrorCode.getMessage(ErrorCode.RESOURCE_NOT_FOUND));
        response.put("path", ctx.path());
        response.put("timestamp", System.currentTimeMillis());
        
        ctx.status(404).json(response);
    }
    
    public static void handleUnauthorized(Context ctx) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", ErrorCode.AUTH_FAILED);
        response.put("message", ErrorCode.getMessage(ErrorCode.AUTH_FAILED));
        response.put("timestamp", System.currentTimeMillis());
        
        ctx.status(401).json(response);
    }
    
    public static void handleForbidden(Context ctx) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", ErrorCode.AUTH_PERMISSION_DENIED);
        response.put("message", ErrorCode.getMessage(ErrorCode.AUTH_PERMISSION_DENIED));
        response.put("timestamp", System.currentTimeMillis());
        
        ctx.status(403).json(response);
    }
    
    public static void handleBadRequest(Context ctx, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", ErrorCode.INVALID_PARAMETER);
        response.put("message", message);
        response.put("timestamp", System.currentTimeMillis());
        
        ctx.status(400).json(response);
    }
    
    private static String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }
}
