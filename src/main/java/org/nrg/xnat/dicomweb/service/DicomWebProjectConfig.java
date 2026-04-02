package org.nrg.xnat.dicomweb.service;

import org.nrg.config.entities.Configuration;
import org.nrg.framework.constants.Scope;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.security.UserI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-project DICOMweb configuration using XNAT's config service.
 * Stores project-level settings at tool="dicomweb", path="site-wide-opt-out".
 */
@Service
public class DicomWebProjectConfig {

    private static final Logger log = LoggerFactory.getLogger(DicomWebProjectConfig.class);
    private static final String TOOL = "dicomweb";
    private static final String PATH = "site-wide-opt-out";

    /**
     * Check if a project has opted out of site-wide DICOMweb queries.
     *
     * @param projectId the project ID
     * @return true if the project is excluded from site-wide queries
     */
    public boolean isProjectExcludedFromSiteWide(String projectId) {
        try {
            String config = XDAT.getConfigService().getConfigContents(TOOL, PATH, Scope.Project, projectId);
            return "true".equalsIgnoreCase(config != null ? config.trim() : "");
        } catch (Exception e) {
            log.debug("No site-wide opt-out config for project {}, defaulting to included", projectId);
            return false;
        }
    }

    /**
     * Get the set of project IDs that have opted out of site-wide DICOMweb queries.
     *
     * @return set of project IDs that have opted out
     */
    public Set<String> getOptedOutProjectIds() {
        try {
            return XDAT.getConfigService().getConfigsByTool(TOOL).stream()
                    .filter(c -> PATH.equals(c.getPath()))
                    .filter(c -> Scope.Project.equals(c.getScope()))
                    .filter(c -> Configuration.ENABLED_STRING.equals(c.getStatus()))
                    .filter(c -> "true".equalsIgnoreCase(
                            c.getContents() != null ? c.getContents().trim() : ""))
                    .map(Configuration::getEntityId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.debug("Failed to get opted-out projects, defaulting to empty set", e);
            return Collections.emptySet();
        }
    }

    /**
     * Set whether a project opts out of site-wide DICOMweb queries.
     *
     * @param projectId the project ID
     * @param excluded  true to exclude from site-wide queries
     * @param user      the user making the change (must be project owner or admin)
     */
    public void setProjectExcludedFromSiteWide(String projectId, boolean excluded, UserI user) {
        try {
            XDAT.getConfigService().replaceConfig(
                    user.getUsername(),
                    "Set DICOMweb site-wide opt-out",
                    TOOL, PATH,
                    Boolean.toString(excluded),
                    Scope.Project, projectId
            );
            log.info("Project {} site-wide opt-out set to {} by {}", projectId, excluded, user.getUsername());
        } catch (Exception e) {
            log.error("Failed to set site-wide opt-out for project {}", projectId, e);
            throw new RuntimeException("Failed to update project configuration", e);
        }
    }
}
