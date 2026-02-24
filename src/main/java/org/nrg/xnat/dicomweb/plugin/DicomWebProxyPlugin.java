package org.nrg.xnat.dicomweb.plugin;

import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.xnat.initialization.RootConfig;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;

@PropertySource("classpath:/config/dicomweb/dicomweb.properties")
@XnatPlugin(
    value = "dicomwebproxy",
    name = "DICOMweb Proxy Plugin",
    description = "Exposes XNAT projects as DICOMweb endpoints for OHIF and VolView",
    logConfigurationFile = "META-INF/resources/dicomweb-logback.xml",
    entityPackages = {"org.nrg.xnat.dicomweb", "org.nrg.xnatx.dicomweb.core.entity"},
    openUrls = {"/xapi/dicomweb/test"}
)
@ComponentScan({"org.nrg.xnat.dicomweb"})
@Import({RootConfig.class})
public class DicomWebProxyPlugin {
}
