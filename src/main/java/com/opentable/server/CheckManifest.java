package com.opentable.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs debug information from the manifest about the name of the system starting up, its version, and the commit ID.
 */
public class CheckManifest {
    private static final String COMMIT = "X-BasePOM-Git-Commit-Id";

    private static final Logger LOG = LoggerFactory.getLogger(CheckManifest.class);

    /**
     * Find all manifests to log out information about them
     * @throws IOException I/O issue reading manifest file
     */
    public void readManifests() throws IOException {
        final Enumeration<URL> urlsEnum = Thread.currentThread().getContextClassLoader().getResources("META-INF/MANIFEST.MF");
        while (urlsEnum.hasMoreElements()) {
            readManifest(urlsEnum.nextElement());
        }
    }

    /**
     * Read the given manifest and log information from it
     * @param url the URL of the manifest to read
     * @throws IOException I/O issue reading manifest file
     */
    private void readManifest(final URL url) throws IOException {
        try (InputStream is = url.openStream()) {
            final Manifest mf = new Manifest();
            mf.read(is);
            final Attributes atts = mf.getMainAttributes();
            LOG.debug("Starting up: {} version {} - built from commit {}",
                    atts.getValue(Attributes.Name.IMPLEMENTATION_TITLE),
                    atts.getValue(Attributes.Name.IMPLEMENTATION_VERSION),
                    atts.getValue(COMMIT)
            );
        }
    }

    /**
     * Attempts to read the manifest and log out information about it when this bean is created at startup
     */
    @PostConstruct
    public void start() {
        try {
            readManifests();
        } catch (IOException e) {
            LOG.debug("Error while reading manifest", e);
        }
    }
}
