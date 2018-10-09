package com.opentable.server;

/**
 * Server Connector Configuration
 */
public interface ServerConnectorConfig {

    /**
     * Get the protocol that the connector handles
     * @return the protocol to handle
     */
    default String getProtocol() {
        return "http";
    }

    /**
     * Get the address to bind the connector to
     * @return the address to bind the connector to
     */
    default String getBindAddress() {
        return null;
    }

    /**
     * Get the port number to use for the connector
     * @return the port number to use, -1 means use next available configured port
     */
    default int getPort() {
        return -1;
    }

    /**
     * Should we treat all requests as secure
     * Used when SSL is terminated externally, e.g. by nginx or elb
     * @return should we force the request to be marked as secure
     */
    default boolean isForceSecure() {
        return false;
    }

    /**
     * Get the name of the keystore
     * @return the name of the keystore
     */
    default String getKeystore() {
        return null;
    }

    /**
     * Get the password to the keystore
     * @return the keystore password
     */
    default String getKeystorePassword() {
        return "changeit";
    }
}
