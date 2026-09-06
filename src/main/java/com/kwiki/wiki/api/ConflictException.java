package com.kwiki.wiki.api;

import com.kk2004.common.exception.BusinessException;

/** Optimistic-lock conflict; the caller can reload and retry. */
public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(409, message);
    }
}
