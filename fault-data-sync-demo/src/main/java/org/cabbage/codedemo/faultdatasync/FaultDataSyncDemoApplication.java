package org.cabbage.codedemo.faultdatasync;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("org.cabbage.codedemo.faultdatasync.mapper")
public class FaultDataSyncDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(FaultDataSyncDemoApplication.class, args);
    }
}
