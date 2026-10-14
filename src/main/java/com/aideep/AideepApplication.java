package com.aideep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AideepApplication {

    public static void main(String[] args) {
        SpringApplication.run(AideepApplication.class, args);
    }
}
