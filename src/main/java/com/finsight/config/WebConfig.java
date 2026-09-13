package com.finsight.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Expo web dev server (a browser origin) to call the API.
 *
 * Native app calls (Expo Go on a phone, or the Expo web-preview's own
 * server-side requests) are never subject to CORS — only requests made by
 * JavaScript running in a browser are, so this is only needed for the "npx
 * expo start --web" flow, not for on-device testing.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
