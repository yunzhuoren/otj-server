package com.opentable.server;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.management.MBeanServer;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.embedded.jetty.JettyServerCustomizer;
import org.springframework.boot.web.embedded.jetty.JettyWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.PropertyResolver;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.opentable.logging.jetty.JsonRequestLog;
import com.opentable.logging.jetty.JsonRequestLogConfig;
import com.opentable.metrics.JettyServerMetricsConfiguration;
import com.opentable.server.HttpServerInfo.ConnectorInfo;
import com.opentable.spring.SpecializedConfigFactory;
import com.opentable.util.Optionals;

/**
 * base class providing common configuration for creating Embedded Jetty instances
 * Spring Boot provides a very basic Jetty integration but it doesn't cover a lot of important use cases.
 * For example even something as trivial as configuring the worker pool size, socket options,
 * or HTTPS connector is totally unsupported.
 */
@Configuration
@Import(JsonRequestLogConfig.class)
public abstract class EmbeddedJettyBase {
    public static final String DEFAULT_CONNECTOR_NAME = "default-http";
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedJettyBase.class);

    @Value("${ot.http.bind-port:#{null}}")
    // Specifying this fails startup
    private String httpBindPort;

    @Value("${ot.httpserver.shutdown-timeout:PT5s}")
    private Duration shutdownTimeout;

    // XXX: these should be removed pending https://github.com/spring-projects/spring-boot/issues/5314
    @Value("${ot.httpserver.max-threads:32}")
    private int maxThreads;

    @Value("${ot.httpserver.min-threads:#{null}}")
    // Specifying this fails the build.
    private Integer minThreads;

    @Value("${ot.httpserver.active-connectors:default-http}")
    List<String> activeConnectors;

    @Value("${ot.httpserver.ssl-allowed-deprecated-ciphers:}")
    List<String> allowedDeprecatedCiphers;

    /**
     * In the case that we bind to port 0, we'll get back a port from the OS.
     * With {@link #containerInitialized(WebServerInitializedEvent)}, we capture this value
     * and store it here.
     */
    private volatile Integer httpActualPort;

    /**
     * Thread pool to use for Jetty requests, typically provided by {@link JettyServerMetricsConfiguration#getIQTPProvider(com.codahale.metrics.MetricRegistry, int)}
     */
    @Inject
    Optional<Provider<QueuedThreadPool>> qtpProvider;

    /**
     * Customizers to customize Handlers, may include:
     *  - {@link JettyServerMetricsConfiguration#getHandlerCustomizer(com.codahale.metrics.MetricRegistry)} to instrument the handlers to report metrics
     */
    @Inject
    Optional<Collection<Function<Handler, Handler>>> handlerCustomizers;

    /**
     * Consumers to customize the server, may include:
     *  - {@link JettyServerMetricsConfiguration#statusReporter(com.codahale.metrics.MetricRegistry)} to report metrics for HTTP statuses
     */
    @Inject
    Optional<Collection<Consumer<Server>>> serverCustomizers;

    @Inject
    Optional<Collection<Consumer<HttpConfiguration>>> httpConfigCustomizers;

    @Inject
    Optional<JsonRequestLog> requestLogger;

    @Inject
    Optional<MBeanServer> mbs;

    private Map<String, ConnectorInfo> connectorInfos;

    /**
     * Create a map of connector names to connector configuration information
     * We take the list of connectors to create from the comma separated list ot.httpserver.active-connectors, or if that's missing default to just default-http,
     * and for each connector on that list we look for config properties named with the pattern ot.httpserver.connector.<connector name>.<config key name> and use it to create
     * config objects.
     *
     * @param configFactory the factory to create config objects, created in {@link ServerConfigConfiguration#connectorConfigs(PropertyResolver)}
     * @return map of connector names to connector configuration information
     */
    @Bean
    Map<String, ServerConnectorConfig> activeConnectors(SpecializedConfigFactory<ServerConnectorConfig> configFactory) {
        final ImmutableMap.Builder<String, ServerConnectorConfig> builder = ImmutableMap.builder();
        activeConnectors.forEach(name -> builder.put(name, configFactory.getConfig(name)));

        final ImmutableMap<String, ServerConnectorConfig> result = builder.build();
        LOG.info("Built active connector list: {}", result);
        return result;
    }


    /**
     * Configures a Web Server Factory with the following customizations:
     *
     * Custom Thread Pool is set if present. The thread pool will have it's min and max threads set to ot.httpserver.max-threads or a default of 32.
     * MBean Server is added if present
     * Any Handler Customizers are applied
     * JSON Request Logging is setup
     * Jetty's Statistics Handler is added
     * Server's graceful stop timeout is set to ot.httpserver.shutdown-timeout (or 5 second default)
     *
     *
     * @param requestLogConfig configuration for request logging
     * @param activeConnectors map of connector name to configuration
     * @param pr the property resolver
     * @param factory the web server factory to customize
     */
    protected void configureFactoryContainer(
            final JsonRequestLogConfig requestLogConfig,
            final Map<String, ServerConnectorConfig> activeConnectors,
            final PropertyResolver pr,
            final WebServerFactoryAdapter<?> factory) {
        if (httpBindPort != null) {
            throw new IllegalStateException("'ot.http.bind-port' is deprecated, refer to otj-server README for replacement");
        }

        final PortIterator ports = new PortIterator(pr);
        final ImmutableMap.Builder<String, ConnectorInfo> connectorInfos = ImmutableMap.builder();
        final ServerConnectorConfig defaultConnector = activeConnectors.get(DEFAULT_CONNECTOR_NAME);

        // Remove Spring Boot's gimped default connector, we'll make a better one
        factory.addServerCustomizers(server -> server.setConnectors(new Connector[0]));
        if (defaultConnector == null) {
            LOG.debug("Disabling default HTTP connector");
            factory.setPort(0);
        }
        if (qtpProvider.isPresent()) {
            factory.setThreadPool(qtpProvider.get().get());
        }
        factory.addServerCustomizers(server -> {
            mbs.ifPresent(m -> server.addBean(new MBeanContainer(m)));
            Handler customizedHandler = server.getHandler();
            if (handlerCustomizers.isPresent()) {
                for (final Function<Handler, Handler> customizer : handlerCustomizers.get()) {
                    customizedHandler = customizer.apply(customizedHandler);
                }
            }

            if (!requestLogConfig.isEnabled()) {
                LOG.debug("request logging disabled; config {}", requestLogConfig);
            } else {
                final RequestLogHandler logHandler = new RequestLogHandler();
                logHandler.setRequestLog(requestLogger.orElseGet(
                        () -> new JsonRequestLog(Clock.systemUTC(), requestLogConfig)));
                logHandler.setHandler(customizedHandler);
                customizedHandler = logHandler;
                LOG.debug("request logging enabled; added log handler with config {}", requestLogConfig);
            }

            // Required for graceful shutdown to work
            final StatisticsHandler stats = new StatisticsHandler();
            stats.setHandler(customizedHandler);
            server.setHandler(stats);

            activeConnectors.forEach((name, config) -> {
                connectorInfos.put(name, createConnector(server, name, ports, config));
            });
            this.connectorInfos = connectorInfos.build();

            server.setStopTimeout(shutdownTimeout.toMillis());
        });
        factory.addServerCustomizers(this::sizeThreadPool);
        factory.addServerCustomizers(server ->
                Optionals.stream(serverCustomizers).flatMap(Collection::stream).forEach(customizer -> {
                    LOG.debug("Customizing server {} with {}", server, customizer);
                    customizer.accept(server);
                }));
    }

    /**
     * Creates connection factories based on configuration, sets up HTTPS security, and selects port.
     * The created connector is added to the server. We return information about the server connector.
     *
     * @param server the server to add the connector to
     * @param name the name of the connector
     * @param port the port for the connector to listen on, used only if the config does not specify a port
     * @param config the connector configuration
     * @return information about the server connector
     */
    @SuppressWarnings("resource")
    @SuppressFBWarnings("SF_SWITCH_FALLTHROUGH")
    private ConnectorInfo createConnector(Server server, String name, IntSupplier port, ServerConnectorConfig config) {
        final List<ConnectionFactory> factories = new ArrayList<>();

        final SslContextFactory ssl;

        switch (config.getProtocol()) { // NOPMD
            case "proxy+http":
                factories.add(new ProxyConnectionFactory());
                //$FALL-THROUGH$
            case "http":
                ssl = null;
                break;
            case "proxy+https":
                factories.add(new ProxyConnectionFactory());
                //$FALL-THROUGH$
            case "https":
                ssl = new SuperSadSslContextFactory(name, config);
                break;
            default:
                throw new UnsupportedOperationException(String.format("For connector '%s', unsupported protocol '%s'", name, config.getProtocol()));
        }

        final HttpConfiguration httpConfig = new HttpConfiguration();
        if (ssl != null) {
            httpConfig.addCustomizer(new SecureRequestCustomizer());
        } else if (config.isForceSecure()) {
            // Used when SSL is terminated externally, e.g. by nginx or elb
            httpConfig.addCustomizer(new SuperSecureCustomizer());
        }
        httpConfigCustomizers.ifPresent(c -> c.forEach(h -> h.accept(httpConfig)));
        final HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);

        if (ssl != null) {
            factories.add(new SslConnectionFactory(ssl, http.getProtocol()));
        }

        factories.add(http);

        final ServerConnector connector = new ServerConnector(server,
                factories.toArray(new ConnectionFactory[factories.size()]));
        connector.setName(name);
        connector.setHost(config.getBindAddress());
        connector.setPort(selectPort(port, config));

        server.addConnector(connector);
        return new ServerConnectorInfo(name, connector, config);
    }

    /**
     * Select a port to use for the connector. If the connector configuration specifies a (non-negative) port, we will use it,
     * otherwise we'll just get the next assigned port (based on config properties PORT0, PORT1, etc., we'll use the next unused one).
     *
     * @param nextAssignedPort supplier that will return the next assigned port
     * @param connectorConfig the server connector configuration
     * @return the port to use
     */
    private int selectPort(IntSupplier nextAssignedPort, ServerConnectorConfig connectorConfig) {
        int configuredPort = connectorConfig.getPort();
        if (configuredPort < 0) {
            return nextAssignedPort.getAsInt();
        }
        return configuredPort;
    }

    /**
     * Size the servlet request thread pool. We set the minimum number of threads and the maximum number of threads in the pool to the same value.
     * This is done because we believe that it always better to eagerly initialize the threads, lest we find out that we can't when we need to.
     *
     * @param server the server to size the thread pool of
     */
    private void sizeThreadPool(Server server) {
        Verify.verify(minThreads == null, "'ot.httpserver.min-threads' has been removed on the " +
                "theory that it is always preferable to eagerly initialize worker threads " +
                "instead of doing so lazily and finding out you can't allocate thread stacks. " +
                "Talk to Platform Architecture if you think you need this tuneable.");

        final QueuedThreadPool qtp = (QueuedThreadPool) server.getThreadPool();
        qtp.setMinThreads(maxThreads);
        qtp.setMaxThreads(maxThreads);
    }

    /**
     * When the web server is initialized, get the actual port it is listening to. Also, save the web server reference.
     * Log a dump of Jetty debug information at the TRACE level. Log thread pool at the info level.
     *
     * @param evt Event to be published after the application context is refreshed and the WebServer is ready.
     * @throws IOException I/O error writing Jetty debug info to the {@link StringBuilder}
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void containerInitialized(final WebServerInitializedEvent evt) throws IOException {
        WebServer container = evt.getWebServer();
        serverHolder().set(container);
        final int port = container.getPort();
        if (port > 0) {
            httpActualPort = port;
        }

        LOG.info("WebServer initialized; pool={}", getThreadPool());
        if (LOG.isTraceEnabled()) {
            final StringBuilder dump = new StringBuilder();
            getServer().dump(dump, "  ");
            LOG.trace("Server configuration: {}", dump);
        }
    }

    // XXX: this is a workaround for
    // https://github.com/spring-projects/spring-boot/issues/4657
    /**
     * Close the {@link WebServer} when the Spring context gets closed
     * @param evt Event raised when an ApplicationContext gets closed.
     */
    @EventListener
    public void gracefulShutdown(ContextClosedEvent evt) {
        WebServer container = serverHolder().get();
        LOG.debug("Received application context closed event {}. Shutting down...", evt);
        LOG.info("Early shutdown of Jetty connectors on {}", container);
        if (container != null) {
            container.stop();
            LOG.info("Jetty is stopped.");
        } else {
            LOG.warn("Never got a Jetty?");
        }
    }

    /**
     * Create a bean with HTTP server info (actual port, connector information, and pool size).
     * @return HTTP Server info
     */
    @Bean
    public HttpServerInfo httpServerInfo() {
        return new HttpServerInfo() {
            @Override
            public int getPort() {
                return getDefaultHttpActualPort();
            }

            @Override
            public Map<String, ConnectorInfo> getConnectors() {
                Preconditions.checkState(connectorInfos != null, "connector info not available yet, please wait for Jetty to be ready");
                return connectorInfos;
            }

            @Override
            public int getPoolSize() {
                return maxThreads;
            }
        };
    }

    /**
     * An atomic reference to the Web Server
     * @return an atomic reference to the web server
     */
    @Bean
    AtomicReference<WebServer> serverHolder() {
        return new AtomicReference<>();
    }

    /**
     * Get the WebServer and expose it as a Spring bean. This bean will be lazily initialized. It may not be available early in the startup process.
     * @return the web server once available
     */
    @VisibleForTesting
    @Bean
    @Lazy
    Server getServer() {
        final WebServer container = serverHolder().get();
        Preconditions.checkState(container != null, "container not yet available");
        return ((JettyWebServer) container).getServer();
    }

    /**
     * Get the Web Server's thread pool
     * @return the Web Server request thread pool
     */
    @VisibleForTesting
    QueuedThreadPool getThreadPool() {
        return (QueuedThreadPool) getServer().getThreadPool();
    }

    /**
     * Get the port we're listening to for HTTP requests
     * @return the actual HTTP port for the default HTTP request
     */
    int getDefaultHttpActualPort() {
        // Safe because state of httpActualPort can only go from null => non null
        Preconditions.checkState(httpActualPort != null, "default connector http port not initialized");
        return httpActualPort;
    }

    /**
     * An SSL Context factory that uses {@link ServerConnectorConfig}'s keystore and keystore password.
     * It also allows the use of deprecated ciphers if they are listed in ot.httpserver.ssl-allowed-deprecated-ciphers
     */
    class SuperSadSslContextFactory extends SslContextFactory {

        /**
         * Create a Super Sad SSL Context Factory
         * @param name the connector's name (used only for logging)
         * @param config the connector configuration, used for keystore name and password
         */
        SuperSadSslContextFactory(String name, ServerConnectorConfig config) {
            super(config.getKeystore());
            Preconditions.checkState(config.getKeystore() != null, "no keystore specified for '%s'", name);
            setKeyStorePassword(config.getKeystorePassword());
        }

        @Override
        protected void removeExcludedCipherSuites(List<String> selected_ciphers) {
            super.removeExcludedCipherSuites(selected_ciphers);

            if (allowedDeprecatedCiphers.isEmpty()) {
                return;
            }

            LOG.warn("***************************************************************************************");
            LOG.warn("TEMPORARILY ALLOWING VULNERABLE, DEPRECATED SSL CIPHERS FOR BUSTED CLIENTS!!!");
            allowedDeprecatedCiphers.forEach(cipher ->
                    LOG.warn("    * {}", cipher)
            );
            LOG.warn("***************************************************************************************");
            selected_ciphers.addAll(allowedDeprecatedCiphers);
        }
    }

    /**
     * Adapter of the Web Server Factory
     *
     * @param <T> the type of {@link WebServerFactory}
     */
    interface WebServerFactoryAdapter<T> {

        /**
         * Set the port to be used by the web server
         * @param port the web server port
         */
        void setPort(int port);

        /**
         * Set Jetty server customizers
         * @param customizers the customizers to apply to the server
         */
        void addServerCustomizers(JettyServerCustomizer... customizers);

        /**
         * Set the session timeout
         * @param duration the session timeout
         */
        void setSessionTimeout(Duration duration);

        /**
         * Set the thread pool to use for requests
         * @param threadPool the thread pool for the web server to use
         */
        void setThreadPool(ThreadPool threadPool);

        /**
         * Set initializers of servlet contexts
         * @param initializers initializers for the web server to use
         */
        void addInitializers(ServletContextInitializer... initializers);

        /**
         * Get the Web Server Factory
         * @return the Web Server Factory
         */
        T getFactory();
    }
}
