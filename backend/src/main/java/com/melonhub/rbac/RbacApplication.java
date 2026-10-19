package com.melonhub.rbac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RbacApplication {
    public static void main(String[] args) { SpringApplication.run(RbacApplication.class, args); }
}
