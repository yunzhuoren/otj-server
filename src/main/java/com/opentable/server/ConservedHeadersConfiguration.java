package com.opentable.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.opentable.conservedheaders.ClientConservedHeadersFeature;
import com.opentable.jaxrs.JaxRsFeatureBinding;
import com.opentable.jaxrs.StandardFeatureGroup;

/**
 * Sets up conserved header functionality that will conserve headers from a request when making downstream requests
 */
@Configuration
@Import(com.opentable.conservedheaders.ConservedHeadersConfiguration.class)
public class ConservedHeadersConfiguration {

    /**
     * Creates a JAX-RS Feature Binding associating the Platform Internal feature group with the filter that adds conserved headers to client requests
     * @param feature the JAX-RS feature that adds conserved headers to client requests
     * @return  the JAX-RS feature binding
     */
    @Bean
    JaxRsFeatureBinding getConserveHeadersFeatureBinding(final ClientConservedHeadersFeature feature) {
        return JaxRsFeatureBinding.bind(StandardFeatureGroup.PLATFORM_INTERNAL, feature);
    }
}
