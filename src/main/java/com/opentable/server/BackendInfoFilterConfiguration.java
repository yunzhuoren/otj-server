package com.opentable.server;

import java.io.IOException;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.ImmutableMap;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.opentable.service.AppInfo;
import com.opentable.service.ServiceInfo;

/**
 * Configures and creates a filter that adds headers with prefix {@link #HEADER_PREFIX} with some information about the backend that actually
 * handled the request. The Front Door filters out these headers for public-facing instances.
 */
@Configuration
@Import(BackendInfoFilterConfiguration.BackendInfoFilter.class)
public class BackendInfoFilterConfiguration {
    public static final String HEADER_PREFIX = "X-OT-Backend-";

    /**
     * Creates a filter registration bean for the backend info filter. This will cause Spring to automatically register this bean on the servlet container.
     *
     * @param filter the backend info filter to register
     * @return the filter registration bean
     */
    @Bean
    public FilterRegistrationBean<BackendInfoFilter> getBackendInfoFilterRegistrationBean(final BackendInfoFilter filter) {
        return new FilterRegistrationBean<>(filter);
    }

    /**
     * The backend info filter. This adds headers with the prefix X-OT-Backend- that have info about this backend
     */
    public static class BackendInfoFilter implements Filter {
        private final Map<String, String> headers;

        /**
         * Construct a backend info filter. This creates the headers to add.
         *
         * @param appInfo information about this environment and the application it is deployed in
         * @param serviceInfo information about this service
         */
        BackendInfoFilter(final AppInfo appInfo, final ServiceInfo serviceInfo) {
            headers = assembleInfo(appInfo, serviceInfo);
        }

        @Override
        public void init(final FilterConfig filterConfig) throws ServletException {}

        @Override
        public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
                throws IOException, ServletException {
            // If transfer encoding ends up being chunked, setting these after execution of the filter chain results
            // in these added headers being ignored.  We therefore add them before chain execution.  See OTPL-1698.
            if (response instanceof HttpServletResponse) {
                final HttpServletResponse httpResponse = (HttpServletResponse) response;
                headers.forEach(httpResponse::addHeader);
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {}

        /**
         * Creates a map of headers about backend info to add to responses
         * @return map of headers we'll add to responses; unavailable information will result in headers
         * not being set
         */
        private Map<String, String> assembleInfo(final AppInfo appInfo, final ServiceInfo serviceInfo) {
            final ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
            builder.put(named("Service-Name"), serviceInfo.getName());

            if (appInfo.getBuildTag() != null) {
                builder.put(named("Build-Tag"), appInfo.getBuildTag());
            }

            final Integer instanceNo = appInfo.getInstanceNumber();
            if (instanceNo != null) {
                builder.put(named("Instance-No"), instanceNo.toString());
            }

            if (appInfo.getTaskHost() != null) {
                builder.put(named("Task-Host"), appInfo.getTaskHost());
            }

            return builder.build();
        }

        /**
         * Get the given header name after adding the prefix {@code X-OT-Backend-}
         * @param name the name to prefix with {@code X-OT-Backend-}
         * @return the header prefixed by {@code X-OT-Backend-}
         */
        private static String named(final String name) {
            return HEADER_PREFIX + name;
        }
    }
}
