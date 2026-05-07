package com.recallai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {
    private String url;
    private String model;
    private int timeoutMs = 30000;
}
