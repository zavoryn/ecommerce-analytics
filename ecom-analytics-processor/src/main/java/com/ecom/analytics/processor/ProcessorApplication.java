package com.ecom.analytics.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@EnableDiscoveryClient
@SpringBootApplication
public class ProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessorApplication.class, args);
    }
}
