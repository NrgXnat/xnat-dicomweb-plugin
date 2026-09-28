/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service.impl.strategy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link QueuedBuildingStatusInvoker}. XNAT 1.10.1 and earlier declare
 * {@code void setStatusToQueuedBuilding(long)}; XNAT 1.10.2 declares {@code boolean setStatusToQueuedBuilding(long)}.
 * The two test interfaces below stand in for those two versions of DirectArchiveSessionHibernateService.
 */
public class QueuedBuildingStatusInvokerTest {

    public interface VoidSignatureService {
        void setStatusToQueuedBuilding(long id) throws Exception;
    }

    public interface BooleanSignatureService {
        boolean setStatusToQueuedBuilding(long id) throws Exception;
    }

    public interface OverloadedService {
        boolean setStatusToQueuedBuilding(long id) throws Exception;

        boolean setStatusToQueuedBuilding(long id, boolean force) throws Exception;
    }

    public interface UnsupportedReturnTypeService {
        Object setStatusToQueuedBuilding(long id) throws Exception;
    }

    public interface MissingMethodService {
        void somethingElse(long id);
    }

    public static class CheckedTestException extends Exception {
        public CheckedTestException(String message) {
            super(message);
        }
    }

    @Test
    public void voidSignatureIsCalledAndTreatedAsQueued() throws Exception {
        final List<Long> calls = new ArrayList<>();
        final VoidSignatureService service = calls::add;

        final boolean queued = QueuedBuildingStatusInvoker.queueForBuilding(VoidSignatureService.class, service, 42L);

        assertTrue(queued);
        assertEquals(1, calls.size());
        assertEquals(Long.valueOf(42L), calls.get(0));
    }

    @Test
    public void booleanSignatureTrueIsReturned() throws Exception {
        final List<Long> calls = new ArrayList<>();
        final BooleanSignatureService service = id -> calls.add(id);

        final boolean queued = QueuedBuildingStatusInvoker.queueForBuilding(BooleanSignatureService.class, service, 7L);

        assertTrue(queued);
        assertEquals(Long.valueOf(7L), calls.get(0));
    }

    @Test
    public void booleanSignatureFalseIsReturned() throws Exception {
        final BooleanSignatureService service = id -> false;

        assertFalse(QueuedBuildingStatusInvoker.queueForBuilding(BooleanSignatureService.class, service, 7L));
    }

    @Test
    public void checkedExceptionFromServiceIsRethrownUnwrapped() {
        final CheckedTestException thrown = new CheckedTestException("session gone");
        final VoidSignatureService service = id -> {
            throw thrown;
        };

        try {
            QueuedBuildingStatusInvoker.queueForBuilding(VoidSignatureService.class, service, 1L);
            fail("Expected the service's exception to propagate");
        } catch (Exception e) {
            assertSame(thrown, e);
        }
    }

    @Test
    public void runtimeExceptionFromServiceIsRethrownUnwrapped() {
        final IllegalStateException thrown = new IllegalStateException("boom");
        final BooleanSignatureService service = id -> {
            throw thrown;
        };

        try {
            QueuedBuildingStatusInvoker.queueForBuilding(BooleanSignatureService.class, service, 1L);
            fail("Expected the service's exception to propagate");
        } catch (Exception e) {
            assertSame(thrown, e);
        }
    }

    @Test
    public void missingMethodFailsWithCatchableException() {
        final MissingMethodService service = id -> { };

        try {
            QueuedBuildingStatusInvoker.queueForBuilding(MissingMethodService.class, service, 1L);
            fail("Expected an exception when the method does not exist");
        } catch (Exception e) {
            assertTrue(e instanceof IllegalStateException);
        }
    }

    @Test
    public void singleArgumentOverloadIsSelectedWhenForceOverloadExists() throws Exception {
        final List<String> calls = new ArrayList<>();
        final OverloadedService service = new OverloadedService() {
            @Override
            public boolean setStatusToQueuedBuilding(final long id) {
                calls.add("single:" + id);
                return true;
            }

            @Override
            public boolean setStatusToQueuedBuilding(final long id, final boolean force) {
                calls.add("force:" + id + ":" + force);
                return true;
            }
        };

        assertTrue(QueuedBuildingStatusInvoker.queueForBuilding(OverloadedService.class, service, 3L));
        assertEquals(Collections.singletonList("single:3"), calls);
    }

    @Test
    public void errorFromServiceIsRethrownUnwrapped() throws Exception {
        final AssertionError thrown = new AssertionError("linkage");
        final VoidSignatureService service = id -> {
            throw thrown;
        };

        try {
            QueuedBuildingStatusInvoker.queueForBuilding(VoidSignatureService.class, service, 1L);
            fail("Expected the service's error to propagate");
        } catch (AssertionError e) {
            assertSame(thrown, e);
        }
    }

    @Test
    public void unsupportedReturnTypeFailsWithoutCallingTheService() {
        final List<Long> calls = new ArrayList<>();
        final UnsupportedReturnTypeService service = id -> {
            calls.add(id);
            return Optional.empty();
        };

        try {
            QueuedBuildingStatusInvoker.queueForBuilding(UnsupportedReturnTypeService.class, service, 1L);
            fail("Expected an exception for a return type other than void or boolean");
        } catch (Exception e) {
            assertTrue(e instanceof IllegalStateException);
        }
        assertTrue("The service must not be called when its result cannot be interpreted", calls.isEmpty());
    }
}
