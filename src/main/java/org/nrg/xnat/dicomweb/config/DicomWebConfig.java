package org.nrg.xnat.dicomweb.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * DICOMweb Plugin Configuration
 *
 * <p>Note: multipart/related handling is done in XNAT core's WebConfig.java
 * (via patch to skip multipart/related in StandardServletMultipartResolver)
 *
 * <p>Configuration is managed via XNAT's preference system (@NrgPreferenceBean)
 *
 * <p>Component scanning includes:
 * <ul>
 *   <li>org.nrg.xnat.dicomweb - DICOMweb proxy plugin components</li>
 *   <li>org.nrg.xnatx.dicomweb.core - Shared DICOMweb core library components</li>
 * </ul>
 */
@Configuration
@ComponentScan({"org.nrg.xnat.dicomweb", "org.nrg.xnatx.dicomweb.core"})
@Slf4j
public class DicomWebConfig {
    /**
     * Inject preference bean to ensure it's registered with XNAT's preference system
     */
    @Autowired
    public DicomWebConfig(DicomWebPreferenceBean preferenceBean) {
        log.info("DicomWebConfig initialized with preference bean: {}", preferenceBean.getClass().getSimpleName());
    }
}
