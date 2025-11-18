package org.nrg.xnat.dicomweb.config;

import org.nrg.xnat.dicomweb.filter.StowRsRequestCachingFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan({"org.nrg.xnat.dicomweb"})
public class DicomWebConfig {

    /**
     * Register the STOW-RS request caching filter as a Spring bean.
     * The @Component annotation on the filter class will handle registration.
     */
    @Bean
    public StowRsRequestCachingFilter stowRsRequestCachingFilter() {
        return new StowRsRequestCachingFilter();
    }
}
