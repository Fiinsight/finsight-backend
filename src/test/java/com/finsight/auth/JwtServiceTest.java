package com.finsight.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void rejectsMissingOrPublicJwtSecret() {
        assertThatThrownBy(() -> new JwtService("", 86400))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService("Zm9yLWxvY2FsLWRldmVsb3BtZW50LXNlY3JldC1jaGFuZ2UtbWU=", 86400))
                .isInstanceOf(IllegalStateException.class);
    }
}
