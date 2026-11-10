package com.jobradar.notifier.internal;

/** Turns a stored CV key into a link the user can open. Public so api tests can replace it. */
public interface CvLinks {

    String presign(String key);
}
