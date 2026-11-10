package com.jobradar.profile.seed;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports the seed file named by {@code jobradar.profile.seed-file} when the api starts.
 * Does nothing when the property is empty.
 */
@Component
class ProfileSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProfileSeedRunner.class);

    private final ProfileSeedImporter importer;
    private final JsonMapper json;
    private final String seedFile;

    ProfileSeedRunner(
            ProfileSeedImporter importer,
            JsonMapper json,
            @Value("${jobradar.profile.seed-file:}") String seedFile) {
        this.importer = importer;
        this.json = json;
        this.seedFile = seedFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedFile.isBlank()) {
            return;
        }
        ProfileSeed seed = json.readValue(Path.of(seedFile).toFile(), ProfileSeed.class);
        ProfileSeedImporter.Result result = importer.importSeed(seed);
        // The user id is logged, never the email: logs must not carry personal data.
        log.info(
                "Profile seed imported for user {}: {} bullets inserted, {} already present",
                result.userId(), result.bulletsInserted(), result.bulletsAlreadyPresent());
    }
}
