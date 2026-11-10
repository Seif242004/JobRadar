package com.jobradar.tailoring.internal;

/** Heading data of one job, project or skill row. */
public record CvEntry(long id, String section, String title, String dates, String context, int sortOrder) {}
