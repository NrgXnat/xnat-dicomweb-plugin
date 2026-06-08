package org.nrg.xnat.dicomweb.config;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xnat.dicomweb.service.impl.strategy.DicomImportStrategy;
import org.nrg.xnat.dicomweb.util.AcceptParamWildcardInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.accept.ParameterContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.Collections;

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
 *   <li>org.nrg.xnat.dicomweb - DICOMweb plugin components</li>
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

    /**
     * Honor the {@code accept} query parameter as an alternative source of
     * negotiated media types, per DICOM PS3.18 Section 8.3.3.1. This makes
     * Spring's {@code produces} matching consult either the {@code Accept}
     * header or the {@code accept} query parameter, so a client that cannot
     * set HTTP headers can still reach DICOMweb endpoints by URL alone.
     * Without this configuration Spring would route purely on the Accept
     * header and return 406 for query-parameter-only requests.
     *
     * <p>The stock {@link ParameterContentNegotiationStrategy} only resolves
     * the parameter value against a registered key→type map (e.g.
     * {@code ?accept=json} → {@code application/json}) and silently falls
     * back to {@code *}/{@code *} for unknown keys. DICOMweb sends full
     * media types in the parameter ({@code ?accept=multipart/related}),
     * so we override {@code handleNoMatch} to parse the value directly.
     */
    @Bean
    public WebMvcConfigurer dicomwebContentNegotiation() {
        return new WebMvcConfigurer() {
            @Override
            public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
                final ParameterContentNegotiationStrategy paramStrategy =
                        new ParameterContentNegotiationStrategy(Collections.emptyMap()) {
                            @Override
                            protected MediaType handleNoMatch(NativeWebRequest request, String key) {
                                try {
                                    return MediaType.parseMediaType(key);
                                } catch (InvalidMediaTypeException e) {
                                    return null;
                                }
                            }
                        };
                paramStrategy.setParameterName("accept");
                configurer.strategies(Arrays.asList(
                        paramStrategy,
                        new HeaderContentNegotiationStrategy()));
            }

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new AcceptParamWildcardInterceptor())
                        .addPathPatterns("/xapi/dicomweb/**");
            }
        };
    }

    /**
     * Create DirectArchiveStrategy bean only if XNAT provides DirectArchiveSessionService.
     * Older XNAT versions don't have this class, so the bean will be null and
     * StowRsServiceImpl will fall back to GradualDicomImporter only.
     *
     * Uses reflection to avoid loading DirectArchiveSessionService at class-load time,
     * which would cause NoClassDefFoundError on older XNAT versions.
     */
    @Bean(name = "directArchiveStrategy")
    public DicomImportStrategy directArchiveStrategy(DicomWebPreferenceBean preferenceBean) {
        try {
            Class.forName("org.nrg.xnat.archive.services.DirectArchiveSessionService");

            Object sessionService = org.nrg.xdat.XDAT.getContextService().getBean(
                    Class.forName("org.nrg.xnat.archive.services.DirectArchiveSessionService"));
            Object hibernateService = org.nrg.xdat.XDAT.getContextService().getBean(
                    Class.forName("org.nrg.xnat.archive.services.DirectArchiveSessionHibernateService"));

            Class<?> strategyClass = Class.forName(
                    "org.nrg.xnat.dicomweb.service.impl.strategy.DirectArchiveStrategy");
            Object strategy = strategyClass.getConstructors()[0].newInstance(
                    sessionService, hibernateService, preferenceBean);

            log.info("DirectArchiveSessionService available — enabling DirectArchive strategy");
            return (DicomImportStrategy) strategy;
        } catch (ClassNotFoundException e) {
            log.info("DirectArchiveSessionService not available on this XNAT version — " +
                    "DirectArchive strategy will not be available");
            return null;
        } catch (Exception e) {
            log.warn("Failed to initialize DirectArchive strategy: {}", e.getMessage());
            return null;
        }
    }
}
