/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AcceptParamWildcardInterceptorTest {

    private AcceptParamWildcardInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @Before
    public void setUp() {
        interceptor = new AcceptParamWildcardInterceptor();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
    }

    @Test
    public void noAcceptParam_passes() throws Exception {
        when(request.getParameter("accept")).thenReturn(null);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(response, never()).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
    }

    @Test
    public void emptyAcceptParam_passes() throws Exception {
        when(request.getParameter("accept")).thenReturn("");
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(response, never()).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
    }

    @Test
    public void concreteMediaType_passes() throws Exception {
        when(request.getParameter("accept")).thenReturn("application/dicom+json");
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(response, never()).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
    }

    @Test
    public void multipleConcreteMediaTypes_pass() throws Exception {
        when(request.getParameter("accept")).thenReturn("image/jpeg, image/png");
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(response, never()).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
    }

    @Test
    public void typeWildcard_rejected() throws Exception {
        when(request.getParameter("accept")).thenReturn("*/*");
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
    }

    @Test
    public void subtypeWildcard_rejected() throws Exception {
        when(request.getParameter("accept")).thenReturn("image/*");
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
    }

    @Test
    public void wildcardAmongConcreteTypes_rejected() throws Exception {
        when(request.getParameter("accept")).thenReturn("application/dicom+json, */*");
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
    }
}
