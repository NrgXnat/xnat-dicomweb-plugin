# XNAT DICOMweb Plugin - Deployment Checklist

## Pre-Deployment

- [ ] **Verify XNAT Version**
  ```bash
  # Check your XNAT version
  grep XNAT_VERSION $XNAT_HOME/config/xnat-conf.properties
  ```
  - Must be XNAT 1.9.x
  - If using different version, code may need adjustment

- [ ] **Check Java Version**
  ```bash
  java -version
  ```
  - Must be Java 8 or higher

- [ ] **Verify Archive Path**
  ```bash
  # Default is /data/xnat/archive
  ls -la /data/xnat/archive
  ```
  - Ensure DICOM files are accessible
  - Note custom path if different

- [ ] **Check DICOM Data**
  ```bash
  # Verify DICOM files have required UIDs
  dcmdump /path/to/dicom/file.dcm | grep -E "StudyInstanceUID|SeriesInstanceUID|SOPInstanceUID"
  ```

## Build

- [ ] **Build Plugin**
  ```bash
  cd /path/to/xnat_dicomweb_plugin
  ./gradlew clean build -x test
  ```
  - Expected output: `BUILD SUCCESSFUL`
  - JAR location: `build/libs/xnat-dicomweb-plugin-1.1.1.jar`
  - JAR size: ~20KB

- [ ] **Verify JAR Contents**
  ```bash
  jar tf build/libs/xnat-dicomweb-plugin-1.1.1.jar | grep DicomWeb
  ```
  - Should see plugin classes

## Installation

- [ ] **Backup Current Setup**
  ```bash
  # Backup plugins directory
  tar -czf xnat-plugins-backup-$(date +%F).tar.gz $XNAT_HOME/plugins/
  ```

- [ ] **Copy Plugin to XNAT**
  ```bash
  cp build/libs/xnat-dicomweb-plugin-1.1.1.jar $XNAT_HOME/plugins/
  chmod 644 $XNAT_HOME/plugins/xnat-dicomweb-plugin-1.1.1.jar
  ```

- [ ] **Configure Archive Path (if custom)**
  ```bash
  # Edit Tomcat/XNAT startup script
  # Add: -Dxnat.archive=/your/custom/path
  ```

- [ ] **Restart XNAT**
  ```bash
  sudo systemctl stop tomcat9
  sudo systemctl start tomcat9
  ```

## Verification

- [ ] **Check Plugin Loaded**
  ```bash
  tail -100 $XNAT_HOME/logs/catalina.out | grep -i dicomweb
  ```
  - Look for: "Loading plugin: dicomwebplugin"
  - Look for: "Loaded plugin: DICOMweb Plugin"
  - No errors should appear

- [ ] **Verify Swagger UI**
  - Navigate to: `http://your-xnat/xapi/swagger-ui.html`
  - Look for "DICOMweb QIDO-RS API" section
  - Look for "DICOMweb WADO-RS API" section

- [ ] **Test Search Endpoint**
  ```bash
  curl -u username:password \
    "http://your-xnat/xapi/dicomweb/projects/PROJECT_ID/studies"
  ```
  - Should return JSON array (may be empty if no studies)
  - Should NOT return 404 or 500 error

- [ ] **Test with Actual Data**
  ```bash
  # If you have studies in the project
  curl -u username:password \
    "http://your-xnat/xapi/dicomweb/projects/PROJECT_ID/studies" | jq
  ```
  - Should return studies with StudyInstanceUID
  - Verify attributes are populated

## CORS Configuration (for Web Viewers)

- [ ] **Configure CORS in XNAT**
  ```bash
  # Edit xnat-conf.properties
  vi $XNAT_HOME/config/xnat-conf.properties
  ```
  
  Add:
  ```properties
  security.cors.allowed-origins=https://ohif.example.com,https://volview.example.com
  security.cors.allowed-methods=GET,POST,OPTIONS
  security.cors.allowed-headers=*
  security.cors.max-age=3600
  ```

- [ ] **Restart after CORS changes**
  ```bash
  sudo systemctl restart tomcat9
  ```

- [ ] **Test CORS**
  ```bash
  curl -H "Origin: https://ohif.example.com" \
       -H "Access-Control-Request-Method: GET" \
       -X OPTIONS \
       "http://your-xnat/xapi/dicomweb/projects/PROJECT_ID/studies"
  ```
  - Should see Access-Control headers in response

## OHIF Viewer Setup

- [ ] **Create OHIF Configuration**
  - File: `app-config.js`
  - Set DICOMweb URLs to XNAT endpoints
  - Use project-specific URL pattern

- [ ] **Test OHIF Connection**
  - Load OHIF in browser
  - Should see study list
  - Click study to load images
  - Verify images display correctly

- [ ] **Check Browser Console**
  - No CORS errors
  - No 404/500 errors
  - DICOMweb requests succeed

## VolView Setup

- [ ] **Configure VolView**
  - Open VolView desktop app
  - Go to File → Remote
  - Enter DICOMweb URL

- [ ] **Test Authentication**
  - Enter XNAT username/password
  - Should connect successfully

- [ ] **Test Data Access**
  - Browse studies
  - Load images
  - Verify rendering

## Performance Testing

- [ ] **Test with Large Project**
  - Query project with 100+ studies
  - Should return in < 5 seconds
  - Check logs for errors

- [ ] **Test Image Retrieval**
  - Load study with multiple series
  - All images should load
  - No timeout errors

- [ ] **Monitor Resource Usage**
  ```bash
  # Check Tomcat memory usage
  ps aux | grep tomcat
  
  # Check XNAT logs for memory issues
  grep -i "OutOfMemoryError" $XNAT_HOME/logs/catalina.out
  ```

## Production Hardening

- [ ] **Restrict CORS Origins**
  - Change from `*` to specific domains
  - Test from allowed origins

- [ ] **Enable HTTPS Only**
  - Ensure all connections use HTTPS
  - Redirect HTTP to HTTPS

- [ ] **Set Up Monitoring**
  - Monitor endpoint response times
  - Alert on errors
  - Track usage metrics

- [ ] **Configure Logging Level**
  ```properties
  # In log4j.properties
  log4j.logger.org.nrg.xnat.dicomweb=INFO
  ```
  - Use INFO for production
  - Use DEBUG only for troubleshooting

- [ ] **Database Optimization**
  ```sql
  -- Add index for UID lookups
  CREATE INDEX IF NOT EXISTS idx_imagesession_uid 
  ON xnat_imagesessiondata(uid, project);
  ```

## Troubleshooting

- [ ] **No Studies Returned**
  - Check project has imaging sessions
  - Verify sessions have UID field set
  - Check user has project access
  - Review database queries in logs

- [ ] **File Not Found Errors**
  - Verify archive path is correct
  - Check file permissions
  - Confirm DICOM resource label is "DICOM"
  - Review getResourcePath() logic

- [ ] **Performance Issues**
  - Add database indexes
  - Implement caching
  - Check disk I/O performance
  - Review large file handling

## Rollback Plan

If issues occur:

- [ ] **Stop XNAT**
  ```bash
  sudo systemctl stop tomcat9
  ```

- [ ] **Remove Plugin**
  ```bash
  rm $XNAT_HOME/plugins/xnat-dicomweb-plugin-1.1.1.jar
  ```

- [ ] **Clear Cache**
  ```bash
  rm -rf $XNAT_HOME/work/Catalina/
  ```

- [ ] **Restart XNAT**
  ```bash
  sudo systemctl start tomcat9
  ```

- [ ] **Restore from Backup** (if needed)
  ```bash
  tar -xzf xnat-plugins-backup-YYYY-MM-DD.tar.gz -C $XNAT_HOME/
  ```

## Post-Deployment

- [ ] **Document Configuration**
  - Record custom archive paths
  - Note CORS settings
  - Document any code modifications

- [ ] **Train Users**
  - Demonstrate OHIF/VolView access
  - Provide viewer configuration
  - Share troubleshooting guide

- [ ] **Set Up Support**
  - Monitor for issues
  - Plan regular updates
  - Establish feedback channel

## Success Criteria

- ✅ Plugin loads without errors
- ✅ API endpoints return valid data
- ✅ OHIF/VolView can connect
- ✅ Images load and display correctly
- ✅ Performance is acceptable
- ✅ No security issues
- ✅ Users can access data

## Additional Resources

- **Installation Guide**: `INSTALLATION.md`
- **Implementation Details**: `XNAT_1.9_IMPLEMENTATION.md`
- **Testing Guide**: `TESTING.md`
- **Architecture**: `ARCHITECTURE.md`
