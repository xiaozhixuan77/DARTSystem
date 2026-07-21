package com.dart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DartApplication {

    public static void main(String[] args) {
        SpringApplication.run(DartApplication.class, args);
    }
}
