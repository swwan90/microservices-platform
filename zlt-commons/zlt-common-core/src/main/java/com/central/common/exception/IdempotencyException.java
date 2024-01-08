package com.central.common.exception;

import java.io.Serial;

/**
 * 幂等性异常
 *
 * @author zlt
 */
public class IdempotencyException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 6610083281801529147L;

    public IdempotencyException(String message) {
        super(message);
    }
}
