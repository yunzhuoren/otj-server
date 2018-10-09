package com.opentable.server;

import java.util.Map;

/**
 * Expose information about the currently running HTTP(S) server.
 *
 * Servers have one or more connectors.  The default (but not always present)
 * connector is called {@code default-http}.
 */
public interface HttpServerInfo {
    /**
     * Get the main (usually HTTP) port number
     * @return the main (almost always 'http') port
     */
    int getPort();

    /**
     * Get the size of the request thread pool
     * @return the size of the thread pool
     */
    int getPoolSize();

    /**
     * Get a map of a connector name to information about it for all active server connectors
     * @return information on the currently active server connectors
     */
    Map<String, ConnectorInfo> getConnectors();

    /**
     * Expose information for a single server connector.
     */
    interface ConnectorInfo {
        /**
         * Get the name of the connector
         * @return the configuration name for this connector
         */
        String getName();

        /**
         * Get the protocol the connector is using
         * @return the protocol the connector is expecting to speak
         */
        String getProtocol();

        /**
         * Get the port number that this connector listens on
         * @return the (actual) listen port of this connector
         */
        int getPort();
    }
}
