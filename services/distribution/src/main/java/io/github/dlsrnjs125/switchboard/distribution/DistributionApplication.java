package io.github.dlsrnjs125.switchboard.distribution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DistributionApplication {
    public static final String APPLICATION_NAME = "switchboard-distribution";

    public static void main(String[] args) {
        SpringApplication.run(DistributionApplication.class, args);
    }
}
