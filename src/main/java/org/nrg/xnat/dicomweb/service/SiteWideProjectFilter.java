package org.nrg.xnat.dicomweb.service;

import org.nrg.xnat.dicomweb.config.DicomWebProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Determines which projects are included in site-wide DICOMweb queries
 * by combining site-level blacklist/whitelist with per-project opt-out.
 */
@Component
public class SiteWideProjectFilter {

    private static final Logger log = LoggerFactory.getLogger(SiteWideProjectFilter.class);

    private final DicomWebProperties properties;
    private final DicomWebProjectConfig projectConfig;

    @Autowired
    public SiteWideProjectFilter(DicomWebProperties properties, DicomWebProjectConfig projectConfig) {
        this.properties = properties;
        this.projectConfig = projectConfig;
    }

    /**
     * @return true if site-wide DICOMweb querying is enabled
     */
    public boolean isSiteWideEnabled() {
        return properties.getSiteWide().isEnabled();
    }

    /**
     * Check if a project should be included in site-wide query results.
     *
     * @param projectId the project ID to check
     * @return true if the project is allowed in site-wide results
     */
    public boolean isProjectAllowed(String projectId) {
        if (projectId == null) {
            return false;
        }

        // Check project-level opt-out first
        if (projectConfig.isProjectExcludedFromSiteWide(projectId)) {
            log.debug("Project {} excluded from site-wide queries (project opt-out)", projectId);
            return false;
        }

        // Apply site-level filter
        DicomWebProperties.SiteWideConfig config = properties.getSiteWide();
        Set<String> filterList = config.getProjectSet();
        String mode = config.getFilterMode();

        if ("whitelist".equals(mode)) {
            boolean allowed = filterList.contains(projectId);
            if (!allowed) {
                log.debug("Project {} excluded from site-wide queries (not in whitelist)", projectId);
            }
            return allowed;
        } else {
            // Blacklist mode (default)
            boolean blocked = filterList.contains(projectId);
            if (blocked) {
                log.debug("Project {} excluded from site-wide queries (in blacklist)", projectId);
            }
            return !blocked;
        }
    }

    /**
     * Append SQL WHERE clauses to filter projects for site-wide queries.
     * Combines the site-level whitelist/blacklist with per-project opt-outs
     * into SQL IN/NOT IN conditions.
     *
     * @param sql            the SQL builder to append to (must already have a WHERE clause)
     * @param params         the parameter source to add named parameters to
     * @param projectColumn  the SQL column expression for the project ID (e.g. "e.project")
     */
    public void addProjectFilterToSql(StringBuilder sql, MapSqlParameterSource params,
                                      String projectColumn) {
        DicomWebProperties.SiteWideConfig config = properties.getSiteWide();
        Set<String> filterList = config.getProjectSet();
        String mode = config.getFilterMode();
        Set<String> optedOut = projectConfig.getOptedOutProjectIds();

        if ("whitelist".equals(mode)) {
            // Only include projects on the whitelist, minus any that opted out
            Set<String> allowed = new HashSet<>(filterList);
            allowed.removeAll(optedOut);
            if (allowed.isEmpty()) {
                sql.append("AND 1 = 0 ");
            } else {
                sql.append("AND ").append(projectColumn).append(" IN (:sw_filter_projects) ");
                params.addValue("sw_filter_projects", allowed);
            }
        } else {
            // Blacklist mode: exclude blacklisted + opted-out projects
            Set<String> excluded = new HashSet<>(filterList);
            excluded.addAll(optedOut);
            if (!excluded.isEmpty()) {
                sql.append("AND ").append(projectColumn).append(" NOT IN (:sw_filter_projects) ");
                params.addValue("sw_filter_projects", excluded);
            }
        }
    }
}
