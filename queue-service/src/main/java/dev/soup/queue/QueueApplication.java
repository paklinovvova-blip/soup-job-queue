package dev.soup.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One jar, two roles. The role is the active Spring profile:
 * {@code api} serves HTTP and runs migrations, {@code worker} executes jobs.
 * Both roles run the reaper.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class QueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueueApplication.class, args);
    }
}
