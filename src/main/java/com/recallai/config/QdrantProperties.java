package com.recallai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "qdrant")
public class QdrantProperties {
    private String url;
    private String collection;
    private String collectionTemplated = "inquiry_templated";
    private int timeoutMs = 5000;
}
