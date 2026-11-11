package com.jobradar.tailoring.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Fixed CV parts, read from {@code profile.cv_static}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CvStatic(String name, String headline, List<Contact> contacts, List<Education> education, String languages) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(String text, String url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Education(String title, String dates, String context) {}
}
