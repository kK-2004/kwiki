package com.kwiki.eval;

import com.kk2004.common.exception.BusinessException;

/** 评测相关业务错误；code 为 HTTP 语义码，message 为稳定错误码。 */
public class EvalException extends BusinessException {

    public EvalException(int code, String message) {
        super(code, message);
    }
}
