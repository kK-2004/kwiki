package com.kwiki.wiki.api;

import com.kk2004.common.exception.BusinessException;

/** 乐观锁冲突；调用方可以重新加载后重试。 */
public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(409, message);
    }
}
