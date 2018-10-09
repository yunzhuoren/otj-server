package com.opentable.server;

import java.net.URI;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

/**
 * JAX-RS resource for redirecting HTTP root requests (i.e. "/") to the index.html file
 */
@Path("/")
public class StaticWebrootRedirect {
    private static final String INDEX_FILE_NAME = "index.html";
    private final URI location;

    /**
     * Creates this resource redirecting / to /static/index.html (the default static directory)
     */
    public StaticWebrootRedirect() {
        this(URI.create(String.format("/%s/%s", StaticResourceConfiguration.DEFAULT_PATH_NAME, INDEX_FILE_NAME)));
    }

    /**
     * Create this resource for redirecting / to index.html in the static directory
     * @param config static resource configuration for use constructing the path to index.html
     */
    @Inject
    public StaticWebrootRedirect(final StaticResourceConfiguration config) {
        this(URI.create(config.staticPath(INDEX_FILE_NAME)));
    }

    /**
     * Create a redirect from / to the given location
     * @param location the location to redirect to
     */
    public StaticWebrootRedirect(URI location) {
        this.location = location;
    }

    /**
     * Redirect GET requests to / to the default location
     * @return the temporary redirect
     */
    @GET
    public Response redirect() {
        return Response.temporaryRedirect(location).build();
    }
}
