package com.finsight.external.kis;

import com.fasterxml.jackson.databind.JsonNode;

/** Small shared helper for pulling numeric fields out of KIS JSON responses. */
final class KisJsonNumbers {

    private KisJsonNumbers() {
    }

    static double parseDouble(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return 0.0;
        }
        try {
            return Double.parseDouble(node.get(field).asText().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
