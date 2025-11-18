# GitHub Issue: Add Dedicated Logger File for DICOMweb Plugin

## Title
Add dedicated logger configuration for DICOMweb plugin operations

## Labels
- enhancement
- logging
- operations

## Description

### Problem
Currently, DICOMweb plugin logs are mixed into XNAT's general `application.log` file, making it difficult to:
- Troubleshoot DICOMweb-specific issues
- Monitor QIDO-RS, WADO-RS, and STOW-RS operations
- Debug multipart parsing issues
- Track performance metrics for DICOMweb endpoints

### Proposed Solution
Add a dedicated logger file `dicomweb.log` specifically for DICOMweb plugin operations.

### Implementation

1. **Create logback configuration file**: `src/main/resources/logback-dicomweb.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="DICOMWEB_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${xnat.home}/logs/dicomweb.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>${xnat.home}/logs/dicomweb.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>30</maxHistory>
            <totalSizeCap>1GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="org.nrg.xnat.dicomweb" level="INFO" additivity="false">
        <appender-ref ref="DICOMWEB_FILE"/>
    </logger>
</configuration>
```

2. **Update spring configuration** to include the logback configuration

3. **Benefits**:
   - Separate log file for all DICOMweb operations
   - Easier troubleshooting and debugging
   - Log rotation (daily, keep 30 days, max 1GB)
   - Configurable log levels per component:
     - `org.nrg.xnat.dicomweb.rest` - REST endpoint logs
     - `org.nrg.xnat.dicomweb.service` - Service layer logs
     - `org.nrg.xnat.dicomweb.utils` - Utility logs

4. **Log levels**:
   - INFO: Normal operations (requests, responses, counts)
   - DEBUG: Detailed parsing, DICOM attribute extraction
   - WARN: Issues that don't prevent operation
   - ERROR: Failures, exceptions

### Example Log Output

```
2025-11-17 21:00:00.123 [http-nio-8080-exec-1] INFO  o.n.x.d.r.QidoRsApi - QIDO-RS: Searching studies in project 'test'
2025-11-17 21:00:00.456 [http-nio-8080-exec-1] INFO  o.n.x.d.r.QidoRsApi - QIDO-RS: Found 5 studies
2025-11-17 21:00:05.789 [http-nio-8080-exec-2] INFO  o.n.x.d.r.StowRsApi - STOW-RS: Received upload request from user 'admin' to project 'test'
2025-11-17 21:00:05.800 [http-nio-8080-exec-2] INFO  o.n.x.d.r.StowRsApi - STOW-RS: Parsed 3 DICOM instances from multipart request
2025-11-17 21:00:06.234 [http-nio-8080-exec-2] INFO  o.n.x.d.s.XnatDicomServiceImpl - STOW-RS: Successfully imported 3 instances
```

### Testing
- Verify log file created at `${xnat.home}/logs/dicomweb.log`
- Test log rotation
- Verify no duplicate logs in application.log
- Test log level changes via XNAT admin UI (if supported)

### Acceptance Criteria
- [ ] Dedicated `dicomweb.log` file created
- [ ] All DICOMweb operations logged to dedicated file
- [ ] Log rotation working (daily, 30-day retention)
- [ ] No duplicate entries in application.log
- [ ] Documentation updated in README

### Related Work
- PR #19: STOW-RS implementation (would benefit from dedicated logging)
- Existing QIDO-RS and WADO-RS endpoints

### Priority
Medium - Enhances operations and debugging, not blocking core functionality
