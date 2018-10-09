package com.opentable.server;

import java.util.function.IntSupplier;

import org.springframework.core.env.PropertyResolver;

/**
 * A supplier of port numbers to use. The first time this is called we return the port number configured in the PORT0 property.
 * Subsequent calls will return PORT1, PORT2, etc. If PORT0 is not configured we will return 0 (i.e. arbitrary port).
 * For any port other than 0, there is no default, if a port number is not configured, an IllegalStateException will be thrown.
 *
 * Note: This object is stateful.
 */
class PortIterator implements IntSupplier {
    private final PropertyResolver pr;

    int portIndex = 0;

    /**
     * Create a new port iterator (will start at port 0)
     * @param pr the property resolver to use to find configured port numbers
     */
    PortIterator(PropertyResolver pr) {
        this.pr = pr;
    }

    @Override
    public int getAsInt() {
        final String portN = pr.getProperty("PORT" + portIndex);
        if (portN == null) {
            if (portIndex == 0) {
                // Default is that a single port with no injected ports picks an arbitrary port: "dev mode"
                portIndex++;
                return 0;
            }
            throw new IllegalStateException("PORT" + portIndex + " not set but needed for connector configuration");
        }
        portIndex++;
        return Integer.parseInt(portN);
    }
}
