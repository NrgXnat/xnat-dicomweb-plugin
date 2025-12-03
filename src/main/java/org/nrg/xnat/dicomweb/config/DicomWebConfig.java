package org.nrg.xnat.dicomweb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * DICOMweb Plugin Configuration
 *
 * Note: multipart/related handling is done in XNAT core's WebConfig.java
 * (via patch to skip multipart/related in StandardServletMultipartResolver)
 *
 * Configuration is managed via XNAT's preference system (@NrgPreferenceBean)
 */
@Configuration
@ComponentScan({"org.nrg.xnat.dicomweb"})
public class DicomWebConfig {

    private static final Logger logger = LoggerFactory.getLogger(DicomWebConfig.class);

    /**
     * Inject preference bean to ensure it's registered with XNAT's preference system
     */
    @Autowired
    public DicomWebConfig(DicomWebPreferenceBean preferenceBean) {
        logger.info("DicomWebConfig initialized with preference bean: {}", preferenceBean.getClass().getSimpleName());
    }
}
