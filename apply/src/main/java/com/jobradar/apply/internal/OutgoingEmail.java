package com.jobradar.apply.internal;

/** One email with a PDF attachment. */
public record OutgoingEmail(String from, String to, String subject, String body, String attachmentName, byte[] attachment) {}
