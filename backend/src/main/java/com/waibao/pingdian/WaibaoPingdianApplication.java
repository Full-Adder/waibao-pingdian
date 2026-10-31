package com.waibao.pingdian;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WaibaoPingdianApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaibaoPingdianApplication.class, args);
    }
}
