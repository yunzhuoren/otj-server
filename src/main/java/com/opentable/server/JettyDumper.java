package com.opentable.server;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Provider;

import org.eclipse.jetty.server.Server;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedResource;

/**
 * Logs out a dump of information about the Jetty state when called via JMX
 */
@ManagedResource
public class JettyDumper {

    private final Provider<Server> jetty;

    /**
     * Construct a Jetty dumper
     * @param jetty provider of jetty server
     */
    @Inject
    JettyDumper(Provider<Server> jetty) {
        this.jetty = jetty;
    }

    /**
     * Create a dump of Jetty's state and log it at the INFO level
     * @return the result of the dump
     * @throws IOException Exception writing debug info to the StringBuilder
     */
    @ManagedOperation
    public String dumpJetty() throws IOException {
        final StringBuilder dump = new StringBuilder();
        jetty.get().dump(dump, "  ");
        final String result = dump.toString();
        LoggerFactory.getLogger(JettyDumper.class).info("Jetty Internal State\n{}", result);
        return result;
    }
}
