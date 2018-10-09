package com.opentable.server;

import java.time.Duration;

import com.google.common.annotations.VisibleForTesting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.opentable.util.JvmFallbackShutdown;

@Component
@Import(StartupShutdownFailedHandler.FallbackShutdownExitInterceptor.class)
public class StartupShutdownFailedHandler {
    private static final Logger LOG = LoggerFactory.getLogger(StartupShutdownFailedHandler.class);

    @VisibleForTesting
    static final Duration timeout = Duration.ofSeconds(30);

    /**
     * When this Spring application fails to start, trigger a fallback shutdown thread to kill the JVM in 30 seconds if it's not dead by then.
     * @param event
     */
    @EventListener
    public void onFailure(ApplicationFailedEvent event) {
        LOG.debug("ApplicationFailedEvent {} fallback shutdown {}", event, timeout);
        JvmFallbackShutdown.fallbackTerminate(timeout);
    }

    /**
     * When the Spring application context is closed, , trigger a fallback shutdown thread to kill the JVM in 30 seconds if it's not dead by then.
     * @param event
     */
    @EventListener
    public void onClose(ContextClosedEvent event) {
        LOG.debug("ContextClosedEvent {} fallback shutdown {}", event, timeout);
        JvmFallbackShutdown.fallbackTerminate(timeout);
    }

    /**
     * Exit code generator for Spring. Will initiate a new thread to trigger a fallback shutdown in 30 seconds. Returns 0 as shutdown code.
     * (Fallback termination will use code 254 if it happens)
     */
    static class FallbackShutdownExitInterceptor implements ExitCodeGenerator {
        @Override
        public int getExitCode() {
            LOG.info("SpringApplication exit hook fallback shutdown {}", timeout);
            JvmFallbackShutdown.fallbackTerminate(timeout);
            return 0;
        }
    }
}
