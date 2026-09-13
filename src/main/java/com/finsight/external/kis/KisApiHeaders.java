package com.finsight.external.kis;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Builds the header set every authenticated KIS quotation call needs. */
final class KisApiHeaders {

    private KisApiHeaders() {
    }

    static void apply(HttpHeaders headers, String token, String trId, String appKey, String appSecret) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", trId);
        headers.set("custtype", "P");
        headers.setContentType(MediaType.APPLICATION_JSON);
    }
}
