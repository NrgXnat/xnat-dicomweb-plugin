/********************************************************************
 * Copyright (c) 2023, Institute of Cancer Research
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * (1) Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 * (2) Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *
 * (3) Neither the name of the Institute of Cancer Research nor the
 *     names of its contributors may be used to endorse or promote
 *     products derived from this software without specific prior
 *     written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 *********************************************************************/
package org.nrg.xnat.dicomweb.event;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xdat.om.WrkWorkflowdata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectassessordata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.schema.SchemaElement;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.entities.WorkflowStatusEvent;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.exception.XFTInitException;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.config.DicomWebPreferenceBean;
import org.nrg.xnat.helpers.merge.AnonUtils;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnatx.dicomweb.core.inputcreator.DicomwebInputHandler;
import org.nrg.xnatx.dicomweb.core.toolkit.DicomwebUtils;
import org.nrg.xnatx.dicomweb.core.exceptions.DicomwebMetadataException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import java.util.HashMap;
import java.util.Map;

import static reactor.bus.selector.Selectors.R;

/**
 * Event listener for DICOMweb Proxy plugin that triggers metadata extraction
 * and database caching when DICOM data is modified in XNAT.
 *
 * <p>This listener operates independently from the OHIF viewer plugin and
 * supports ALL DICOM modalities (CT, MR, PT, US, XA, DX, CR, MG, NM, SM, etc.).
 */
@Slf4j
@Service
public class DicomwebProxyEventListener implements Consumer<Event<WorkflowStatusEvent>> {
    private final AnonUtils anonUtils;
    private final DicomwebInputHandler dwInputHandler;
    private final DicomWebPreferenceBean preferences;
    private final Map<String, Boolean> triggerPipelines = new HashMap<>();
    private final Map<String, Boolean> triggerPipelinesSubject = new HashMap<>();

    @Autowired
    public DicomwebProxyEventListener(
            EventBus eventBus, AnonUtils anonUtils, DicomwebInputHandler dwInputHandler,
            DicomWebPreferenceBean preferences) {
        eventBus.on(
                R(
                        WorkflowStatusEvent.class.getName()
                                + "[.]?("
                                + PersistentWorkflowUtils.COMPLETE
                                + ")"),
                this);
        this.anonUtils = anonUtils;
        this.dwInputHandler = dwInputHandler;
        this.preferences = preferences;
        createTriggers();
        log.debug("DICOMweb Proxy event listener initialized");
    }

    @Override
    public void accept(Event<WorkflowStatusEvent> event) {
        final WorkflowStatusEvent wfsEvent = event.getData();
        if (wfsEvent.getWorkflow() instanceof WrkWorkflowdata) {
            handleEvent(wfsEvent);
        }
    }

    private void createTriggers() {
        // Trigger pipelines are the pipeline names of workflow events we should
        // rebuild the DICOMweb metadata for because they indicate modification of
        // DICOM data. A map value of true indicates we should only rebuild if
        // project anon is enabled (otherwise, no changes to DICOM should occur)
        triggerPipelines.put(EventUtils.TRANSFER, false); // Session created
        triggerPipelines.put("Merged", false); // Data added to existing session
        triggerPipelines.put("Removed scan", false);
        triggerPipelines.put("Removed project", false);
        // Special conversion for DICOM uploaded outside of XNAT's normal
        // importers, it sets proper metadata and converts catalogs to DCM type.
        // It does NOT apply anon, but it will be the first we hear of these files
        // as DICOM (a.k.a., no TRANSFER or MERGE event)
        triggerPipelines.put(EventUtils.DICOM_PULL, false);
        // Moving, as opposed to sharing, to a new project applies the target
        // project's anon script
        triggerPipelines.put(EventUtils.MODIFY_PROJECT, true);
        // Moving to another subject reapplies project anon script
        triggerPipelines.put("Modified subject", true);
        // Modifying subject or session label reapplies project anon script
        triggerPipelines.put(EventUtils.RENAME, true);
        triggerPipelinesSubject.put(EventUtils.RENAME, true);
    }

    private void handleEvent(WorkflowStatusEvent wfsEvent) {
        final WrkWorkflowdata workflow = (WrkWorkflowdata) wfsEvent.getWorkflow();
        String pipelineName = workflow.getPipelineName();
        String dataType = workflow.getDataType();
        String id = workflow.getId();

        log.debug(
                "Handling event in DicomwebProxyEventListener. PipelineName: {}, DataType: {}, ID: {}",
                pipelineName,
                dataType,
                id);

        UserI user = workflow.getUser();
        SchemaElement se;
        try {
            se = SchemaElement.GetElement(dataType);
        } catch (XFTInitException | ElementNotFoundException e) {
            log.error("Unable to determine SchemaElement for dataType {}", dataType, e);
            return;
        }

        // Session Deleted event
        if (se.instanceOf(XnatImagesessiondata.SCHEMA_ELEMENT_NAME) && pipelineName.equals("Deleted")) {
            XnatImagesessiondata sessionData =
                    XnatImagesessiondata.getXnatImagesessiondatasById(id, user, false);
            removeDicomwebData(sessionData, id);
            return;
        }

        if (XnatProjectdata.SCHEMA_ELEMENT_NAME.equals(dataType) && pipelineName.equals("Removed Project")) {
            log.trace("project {} deleted; removing cached metadata", id);
            dwInputHandler.deleteDicomwebDataForProject(id);
            return;
        }

        if (XnatSubjectdata.SCHEMA_ELEMENT_NAME.equals(dataType)
                && triggerPipelinesSubject.containsKey(pipelineName)) {
            XnatSubjectdata subjectData = XnatSubjectdata.getXnatSubjectdatasById(id, user, false);

            checkAnonAndGenerateMetadata(
                    subjectData, user, id, pipelineName, triggerPipelinesSubject.get(pipelineName));
        } else if (se.instanceOf(XnatImagesessiondata.SCHEMA_ELEMENT_NAME)
                && triggerPipelines.containsKey(pipelineName)) {
            XnatImagesessiondata sessionData =
                    XnatImagesessiondata.getXnatImagesessiondatasById(id, user, false);
            checkAnonAndGenerateMetadata(
                    sessionData, user, id, pipelineName, triggerPipelines.get(pipelineName));
        }
    }

    private void checkAnonAndGenerateMetadata(
            ArchivableItem item,
            UserI user,
            String id,
            String pipelineName,
            boolean generateOnlyWhenProjectAnonEnabled) {
        if (item == null) {
            log.info("No item for ID: {} User: {} Trigger event: {}", id, user.getUsername(), pipelineName);
            return;
        }
        if (generateOnlyWhenProjectAnonEnabled) {
            String project = item.getProject();
            if (!anonUtils.isProjectScriptEnabled(project)) {
                log.debug("No project anon for project: {}, skipping event: {}", project, pipelineName);
                return;
            }
        }

        if (item instanceof XnatSubjectdata) {
            for (final XnatSubjectassessordata expt :
                    ((XnatSubjectdata) item)
                            .getExperiments_experiment(XnatImagesessiondata.SCHEMA_ELEMENT_NAME)) {
                log.debug(
                        "Extracting DICOMweb metadata for ID: {} User: {} Trigger event: {} (subject)",
                        expt.getId(),
                        user.getUsername(),
                        pipelineName);
                generateMetadata((XnatImagesessiondata) expt);
            }
        } else if (item instanceof XnatImagesessiondata) {
            log.debug(
                    "Extracting DICOMweb metadata for ID: {} User: {} Trigger event: {}",
                    id,
                    user.getUsername(),
                    pipelineName);
            generateMetadata((XnatImagesessiondata) item);
        }
    }

    private void generateMetadata(XnatImagesessiondata item) {
        if (!preferences.getEnableMetadataCache()) {
            log.debug("Metadata caching is disabled, skipping metadata extraction for session {}", item.getId());
            return;
        }
        try {
            if (DicomwebUtils.isSessionValidForDicomweb(item)) {
                dwInputHandler.createDicomwebData(item, true);
            }
        } catch (DicomwebMetadataException ex) {
            log.warn(
                    "Error extracting DICOMweb metadata for session {}: {}",
                    item.getId(),
                    ex.getMessage(),
                    ex);
        }
    }

    private void removeDicomwebData(XnatImagesessiondata sessionData, String id) {
        try {
            // Session Data should be available if the session was deleted from
            // a shared project. If null, then it was deleted from the parent project.
            if (sessionData == null) {
                dwInputHandler.deleteDicomwebData(id);
            }
        } catch (DicomwebMetadataException ex) {
            log.warn(
                    "Error removing DICOMweb metadata for session {}: {}", id, ex.getMessage(), ex);
        }
    }
}
