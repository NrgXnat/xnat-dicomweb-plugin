/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.filter;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that adds a unique request ID to each DICOMweb request
 * for correlation and tracing
 *
 * Note: This filter is registered globally by Spring. To limit it to specific URLs,
 * URL pattern filtering is done in the doFilter method.
 */
@Component
@Order(1)
public class RequestIdFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String MDC_KEY = "requestId";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // No initialization needed
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Only apply to DICOMweb API requests
        String requestUri = httpRequest.getRequestURI();
        if (requestUri != null && requestUri.contains("/dicomweb/")) {
            // Check if request already has an ID, otherwise generate one
            String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
            if (requestId == null || requestId.trim().isEmpty()) {
                requestId = UUID.randomUUID().toString();
            }

            // Store in MDC for logging
            MDC.put(MDC_KEY, requestId);

            // Add to response header
            httpResponse.setHeader(REQUEST_ID_HEADER, requestId);

            try {
                chain.doFilter(request, response);
            } finally {
                // Clean up MDC
                MDC.remove(MDC_KEY);
            }
        } else {
            // Not a DICOMweb request, pass through without modification
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {
        // No cleanup needed
    }
}
