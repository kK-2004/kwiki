package com.kwiki.infrastructure.arcadedb;

/** ArcadeDB HTTP 响应的最小传输表示。 */
public record ArcadeDbResponse(int statusCode, String body) {

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }
}
