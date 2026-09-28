/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service.impl.strategy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Calls {@code DirectArchiveSessionHibernateService.setStatusToQueuedBuilding(long)} by name so the plugin links
 * against every XNAT version. XNAT 1.10.1 and earlier declare it {@code void}; XNAT 1.10.2 declares it
 * {@code boolean}, returning false when the session is no longer RECEIVING (for example, claimed for deletion).
 * A direct call compiled against one version fails with {@link NoSuchMethodError} on the other.
 */
final class QueuedBuildingStatusInvoker {

    private static final String METHOD_NAME = "setStatusToQueuedBuilding";

    private QueuedBuildingStatusInvoker() {
    }

    /**
     * Moves the session to QUEUED_BUILDING.
     *
     * @param serviceType the service interface declaring the method
     * @param service     the service instance to call
     * @param sessionId   the direct archive session ID
     *
     * @return true when the session was queued: always for the {@code void} signature, otherwise the method's result
     *
     * @throws Exception whatever the service method throws, unwrapped; {@link IllegalStateException} when the method
     *                   cannot be found or called
     */
    static boolean queueForBuilding(final Class<?> serviceType, final Object service, final long sessionId) throws Exception {
        final Method method;
        try {
            method = serviceType.getMethod(METHOD_NAME, long.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(serviceType.getName() + " has no method " + METHOD_NAME + "(long)", e);
        }
        try {
            final Object result = method.invoke(service, sessionId);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Failed to call " + METHOD_NAME, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to call " + METHOD_NAME, e);
        }
    }
}
