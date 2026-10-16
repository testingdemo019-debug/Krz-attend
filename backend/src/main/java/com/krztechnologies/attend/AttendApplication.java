package com.krztechnologies.attend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** KRZ Attend backend: the only writer of business data. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AttendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AttendApplication.class, args);
    }
}
