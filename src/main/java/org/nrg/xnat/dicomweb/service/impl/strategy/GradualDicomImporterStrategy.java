/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service.impl.strategy;

import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.xdat.XDAT;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.GradualDicomImporter;
import org.nrg.xnat.dicomweb.helpers.InputStreamFileWriterWrapper;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.nrg.xnat.dicomweb.utils.DicomValidationUtils;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DICOM import strategy using XNAT's GradualDicomImporter.
 * This is XNAT's standard import pipeline which handles:
 * - DICOM parsing and metadata extraction
 * - Session creation in prearchive
 * - File storage with proper directory structure
 */
@Component
public class GradualDicomImporterStrategy implements DicomImportStrategy {

    private static final Logger logger = LoggerFactory.getLogger(GradualDicomImporterStrategy.class);

    @Override
    public String getName() {
        return "GradualDicomImporter";
    }

    @Override
    public void importInstances(UserI user, List<MultipartPart> parts,
                                 Map<String, Object> params,
                                 Set<String> prearchiveUris,
                                 List<FailedInstance> failedInstances) {
        logger.info("Importing {} parts to prearchive via GradualDicomImporter", parts.size());

        for (int i = 0; i < parts.size(); i++) {
            MultipartPart part = parts.get(i);

            // Pre-validation: Check if this looks like a DICOM file
            FailedInstance validationFailure = DicomValidationUtils.validateDicomPart(part, i);
            if (validationFailure != null) {
                logger.warn("Part {} failed pre-validation: {}", i, validationFailure.getErrorMessage());
                failedInstances.add(validationFailure);
                continue;
            }

            try {
                importInstance(user, part, i, params, prearchiveUris);
            } catch (ClientException | ServerException e) {
                logger.error("Failed to import instance {}: {}", i, e.getMessage(), e);
                FailedInstance failure = FailedInstance.processingFailure(i, e.getMessage());
                failedInstances.add(failure);
            }
        }

        logger.info("GradualDicomImporter completed: {} sessions, {} failures",
            prearchiveUris.size(), failedInstances.size());
    }

    /**
     * Import a single DICOM instance using GradualDicomImporter.
     */
    private void importInstance(UserI user, MultipartPart part, int index,
                                Map<String, Object> params, Set<String> uris)
            throws ServerException, ClientException {

        FileWriterWrapperI fw = new InputStreamFileWriterWrapper(part, index);
        logger.debug("Importing DICOM instance: {} ({} bytes)", fw.getName(), part.getSize());

        final GradualDicomImporter importer = new GradualDicomImporter(
            getClass().getName(),
            user,
            fw,
            params
        );

        // Set the DICOM object identifier
        setDicomObjectIdentifier(importer);

        // Import and collect URIs
        try {
            List<String> importedUris = importer.call();
            if (importedUris != null) {
                uris.addAll(importedUris);
                logger.debug("Successfully imported to: {}", importedUris);
            } else {
                logger.warn("Import returned null URIs");
            }
        } catch (Exception e) {
            logger.error("Failed to import DICOM instance: {}", e.getMessage());
            throw new ServerException("DICOM import failed: " + e.getMessage(), e);
        }
    }

    /**
     * Set DICOM object identifier using reflection.
     */
    private void setDicomObjectIdentifier(GradualDicomImporter importer) throws ServerException {
        try {
            Object identifier = XDAT.getContextService().getBean("dicomObjectIdentifier");
            java.lang.reflect.Method setIdentifierMethod = null;
            for (java.lang.reflect.Method method : importer.getClass().getMethods()) {
                if ("setIdentifier".equals(method.getName()) && method.getParameterCount() == 1) {
                    setIdentifierMethod = method;
                    break;
                }
            }
            if (setIdentifierMethod != null) {
                setIdentifierMethod.invoke(importer, identifier);
            } else {
                logger.warn("Could not find setIdentifier method on GradualDicomImporter");
            }
        } catch (Exception e) {
            logger.error("Failed to set DicomObjectIdentifier bean", e);
            throw new ServerException("DICOM import configuration error: " + e.getMessage(), e);
        }
    }
}
