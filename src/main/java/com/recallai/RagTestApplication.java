package com.recallai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan("com.recallai.config")
public class RagTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagTestApplication.class, args);
    }
}
