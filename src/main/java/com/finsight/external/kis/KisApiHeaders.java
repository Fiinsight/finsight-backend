package com.finsight.external.kis;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Builds the header set every authenticated KIS quotation call needs. */
final class KisApiHeaders {

    private static long lastRequestAt;

    private KisApiHeaders() {
    }

    static synchronized void apply(HttpHeaders headers, String token, String trId, String appKey, String appSecret) {
        // ponytail: KIS mock accounts allow roughly one quotation request/sec;
        // one shared gate is enough until traffic needs per-account throttling.
        long wait = 1000L - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", trId);
        headers.set("custtype", "P");
        headers.setContentType(MediaType.APPLICATION_JSON);
    }
}
