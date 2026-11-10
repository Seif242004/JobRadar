package com.jobradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the tailoring-worker deployable.
 *
 * <p>Component scanning starts at {@code com.jobradar}, so this process contains exactly the
 * modules that its pom.xml puts on the classpath: tailoring.
 */
@SpringBootApplication
public class TailoringWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TailoringWorkerApplication.class, args);
    }
}
