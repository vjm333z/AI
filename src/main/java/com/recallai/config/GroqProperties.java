package com.recallai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "groq")
public class GroqProperties {
    private String apiKey;
    private String model;
    private String url;
    private int timeoutMs = 30000;
}
