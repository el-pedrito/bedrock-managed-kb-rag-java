package com.example.techassist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TechAssistApplication {

    public static void main(String[] args) {
        SpringApplication.run(TechAssistApplication.class, args);
    }
}
