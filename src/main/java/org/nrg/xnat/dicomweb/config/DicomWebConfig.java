package org.nrg.xnat.dicomweb.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * DICOMweb Plugin Configuration
 *
 * Note: multipart/related handling is done in XNAT core's WebConfig.java
 * (via patch to skip multipart/related in StandardServletMultipartResolver)
 */
@Configuration
@ComponentScan({"org.nrg.xnat.dicomweb"})
public class DicomWebConfig {
    // Configuration via component scanning only
}
