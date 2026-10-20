package com.kwiki.eval;

/** 评测结果上报失败。 */
public class EvalReportException extends RuntimeException {

    public EvalReportException(String message) {
        super(message);
    }

    public EvalReportException(String message, Throwable cause) {
        super(message, cause);
    }
}
