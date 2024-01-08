package com.central.common.exception;

import java.io.Serial;

/**
 * 业务异常
 *
 * @author zlt
 */
public class BusinessException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 6610083281801529147L;

    public BusinessException(String message) {
        super(message);
    }
}
