package org.cabbage.codedemo.route.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.route.common.Result;
import org.cabbage.codedemo.route.context.RouteContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * @author xzcabbage
 * @since 2025/10/10
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleBusinessException(Exception e, HttpServletRequest request) {
        log.warn("异常: code={}, message={}, path={}, route={}",
                1, e.getMessage(), request.getRequestURI(), RouteContext.getContextInfo());

        return Result.error(
                1,
                e.getMessage()
        );
    }
}
