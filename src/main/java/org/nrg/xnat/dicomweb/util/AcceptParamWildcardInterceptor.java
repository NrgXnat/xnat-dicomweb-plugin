/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects DICOMweb requests whose {@code accept} query parameter contains
 * a wildcard, per DICOM PS3.18 Section 8.3.3.1. Should be registered for
 * the DICOMweb URL path so it runs before any controller method but after
 * Spring's content negotiation has resolved the request mapping.
 *
 * <p>The check is independent of Spring's content negotiation, which is
 * happy to route a wildcard-bearing parameter through to a matching
 * handler. PS3.18 explicitly forbids wildcards in the query parameter
 * (the parameter is for clients that cannot set headers, which do not
 * need to express ranges).</p>
 */
public class AcceptParamWildcardInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        final String acceptParam = request.getParameter("accept");
        if (acceptParam == null || acceptParam.trim().isEmpty()) {
            return true;
        }
        for (MediaTypeNegotiator.ParsedMediaType parsed : MediaTypeNegotiator.parse(acceptParam)) {
            if (parsed.isWildcard() || parsed.isSubtypeWildcard()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "wildcards are not permitted in the accept query parameter (PS3.18 Section 8.3.3.1)");
                return false;
            }
        }
        return true;
    }
}
