package com.jobradar.ingestion.internal.source;

import java.net.URI;

/**
 * The one way connectors and the careers resolver reach the network. Production uses
 * {@link JdkHttpFetcher}; tests pass a fake that serves recorded fixtures, so no test makes a
 * live call.
 */
@FunctionalInterface
public interface HttpFetcher {

    /**
     * Sends one GET request and returns whatever the server answered, including error statuses.
     *
     * @throws SourceFetchException when no answer arrived (DNS, TLS, timeout)
     */
    FetchResponse get(URI uri);
}
