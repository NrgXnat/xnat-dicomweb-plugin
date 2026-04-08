/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */

package org.nrg.xnat.dicomweb.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Filter that caches request bodies for STOW-RS multipart/related requests.
 * This is necessary because Spring's multipart resolver consumes the input stream
 * before our controller can read it.
 *
 * This filter reads the entire body and stores it as a request attribute.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StowRsRequestCachingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(StowRsRequestCachingFilter.class);
    public static final String CACHED_BODY_ATTRIBUTE = "org.nrg.xnat.dicomweb.CACHED_REQUEST_BODY";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        String method = request.getMethod();
        String contentType = request.getContentType();

        logger.debug("StowRsRequestCachingFilter: URI={}, Method={}, ContentType={}", uri, method, contentType);

        // Only cache STOW-RS requests (POST to /dicomweb/.../studies with multipart content)
        if ("POST".equalsIgnoreCase(method) &&
            uri != null && uri.contains("/dicomweb/") && uri.endsWith("/studies") &&
            contentType != null && contentType.toLowerCase().startsWith("multipart/")) {

            // Read entire body into memory
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ServletInputStream inputStream = request.getInputStream();
            byte[] chunk = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, bytesRead);
            }
            byte[] body = buffer.toByteArray();

            logger.info("STOW-RS Filter: Cached {} bytes from input stream", body.length);

            // Store in request attribute
            request.setAttribute(CACHED_BODY_ATTRIBUTE, body);
            logger.debug("STOW-RS Filter: Stored body in request attribute: {}", CACHED_BODY_ATTRIBUTE);

            filterChain.doFilter(request, response);
        } else {
            // All other requests proceed normally
            filterChain.doFilter(request, response);
        }
    }
}
