package dev.soup.queue;

import dev.soup.queue.job.Mutant;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code no-fencing} mutant also drops the effects primary key, so it needs one extra migration.
 * Every other mode uses only {@code db/migration}. The chaos harness checks at start that the schema
 * matches the mode, so a leftover mutant schema cannot go unnoticed.
 */
@Configuration
public class MutantMigrations {

    @Bean
    FlywayConfigurationCustomizer mutantMigrationLocations(QueueProperties props) {
        return config -> {
            if (Mutant.parse(props.mutant()) == Mutant.NO_FENCING) {
                config.locations("classpath:db/migration", "classpath:db/mutant/no-fencing");
            }
        };
    }
}
