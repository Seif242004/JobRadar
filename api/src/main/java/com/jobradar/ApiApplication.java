package com.jobradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the api deployable.
 *
 * <p>Component scanning starts at {@code com.jobradar}, so this process contains exactly the
 * modules that its pom.xml puts on the classpath: profile, matching, apply, notifier, tracker.
 */
@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
