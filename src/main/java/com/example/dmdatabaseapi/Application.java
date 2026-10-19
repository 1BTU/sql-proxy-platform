package com.example.dmdatabaseapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        String dataDir = System.getenv().getOrDefault("APP_DATA_DIR", "./data");
        try {
            Files.createDirectories(Path.of(dataDir));
        } catch (Exception e) {
            throw new IllegalStateException("无法创建平台数据目录: " + dataDir, e);
        }
        SpringApplication.run(Application.class, args);
    }
}
