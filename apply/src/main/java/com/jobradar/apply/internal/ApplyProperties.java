package com.jobradar.apply.internal;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code jobradar.apply.*}.
 *
 * @param followUpDays days after applying until the follow-up reminder (plan: 7 to 10)
 * @param zone         time zone whose midnight starts a new day for the daily limit
 * @param cvBucket     bucket that holds the generated CVs
 */
@ConfigurationProperties("jobradar.apply")
public record ApplyProperties(
        @DefaultValue("8") int followUpDays, @DefaultValue("Africa/Cairo") ZoneId zone, String cvBucket) {}
