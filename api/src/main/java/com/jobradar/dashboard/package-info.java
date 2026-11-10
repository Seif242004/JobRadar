/**
 * Read-only data for the local dashboard (M4). Active only under the {@code local-dashboard}
 * profile, which binds the api to 127.0.0.1, so it is never reachable from outside the machine
 * and never exists in the deployed api.
 *
 * <p>Spring Modulith application module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "dashboard")
package com.jobradar.dashboard;
