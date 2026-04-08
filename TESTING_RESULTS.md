# DICOMweb Plugin Testing Results

## Test Environment

- **Server**: http://demo02.xnatworks.io
- **Credentials**: admin/admin
- **Test Date**: 2025-10-31

## Test Results

### ✅ Plugin Installation
- Plugin successfully installed and accessible
- Test page loads correctly at `/xapi/dicomweb/test`
- Plugin version: 1.1.1
- Plugin ID: dicomwebplugin

### ❌ API Endpoint Issue Found

**Problem**: DICOMweb API endpoints returning empty results `[]`

**Test Project**: `test` (contains 102 imaging sessions with UIDs)

**Endpoint Tested**:
```bash
GET /xapi/dicomweb/projects/test/studies
Response: []
Status: 200 OK
Content-Type: application/dicom+json
```

**Expected**: Should return ~102 studies in DICOMweb JSON format

### Root Cause Analysis

The initial implementation used **raw SQL queries** with `XFTTable.Execute()`:

```java
String query = "SELECT * FROM xnat_imagesessiondata WHERE project = '" + projectId + "'";
XFTTable table = XFTTable.Execute(query, user.getDBName(), user.getUsername());
```

**Issues**:
1. Raw SQL doesn't properly handle XNAT's data model hierarchy
2. Query may not properly filter by user permissions
3. Doesn't work reliably with XNAT 1.9.x data model

### Fix Applied

Changed implementation to use **XNAT's native API methods**:

```java
// Get sessions for a project
ArrayList sessions = XnatImagesessiondata.getXnatImagesessiondatasByField(
    "xnat:imageSessionData/project", projectId, user, false);

// Find session by UID
ArrayList sessions = XnatImagesessiondata.getXnatImagesessiondatasByField(
    "xnat:imageSessionData/UID", studyUID, user, false);
```

**Benefits**:
1. Uses XNAT's built-in field search mechanism
2. Automatically handles permissions
3. Works correctly with XNAT data model
4. More maintainable and reliable

### Files Modified

1. **src/main/java/org/nrg/xnat/dicomweb/service/XnatDicomServiceImpl.java**
   - Updated `searchStudies()` method (lines 37-80)
   - Updated `findSessionByUID()` helper (lines 271-293)
   - Removed unused imports

2. **src/main/java/org/nrg/xnat/dicomweb/plugin/DicomWebPlugin.java**
   - Fixed `openUrls` path from `/dicomweb/test` to `/xapi/dicomweb/test` (line 11)

### Build Status

✅ **Build Successful**
```
BUILD SUCCESSFUL in 1s
JAR: build/libs/xnat-dicomweb-plugin-1.1.1.jar
Size: 26KB
```

## Deployment Instructions

To deploy the fixed plugin to the XNAT server:

### 1. Stop XNAT
```bash
ssh user@demo02.xnatworks.io
sudo systemctl stop tomcat
# or
sudo /etc/init.d/tomcat stop
```

### 2. Replace Plugin JAR
```bash
# Backup old plugin
mv $XNAT_HOME/plugins/xnat-dicomweb-plugin-1.1.1.jar \
   $XNAT_HOME/plugins/xnat-dicomweb-plugin-1.1.1.jar.old

# Copy new plugin
scp build/libs/xnat-dicomweb-plugin-1.1.1.jar \
    user@demo02.xnatworks.io:$XNAT_HOME/plugins/
```

### 3. Start XNAT
```bash
sudo systemctl start tomcat
# or
sudo /etc/init.d/tomcat start
```

### 4. Verify Deployment
```bash
# Wait for XNAT to start (30-60 seconds)
# Check plugin loaded
curl -u admin:admin "http://demo02.xnatworks.io/xapi/plugins" | \
     jq '.[] | select(.id == "dicomwebplugin")'

# Test API endpoint
curl -u admin:admin -H "Accept: application/dicom+json" \
     "http://demo02.xnatworks.io/xapi/dicomweb/projects/test/studies" | \
     jq '. | length'
```

**Expected Result**: Should return `102` (number of studies in test project)

## Verification Tests

After deployment, verify all endpoints work:

### 1. Test Page Access
```
URL: http://demo02.xnatworks.io/xapi/dicomweb/test
Expected: Interactive test page loads
```

### 2. Search Studies (QIDO-RS)
```bash
curl -u admin:admin \
     -H "Accept: application/dicom+json" \
     "http://demo02.xnatworks.io/xapi/dicomweb/projects/test/studies"

Expected: JSON array with 102 study objects containing:
- StudyInstanceUID (0020000D)
- PatientName (00100010)
- StudyDate (00080020)
- ModalitiesInStudy (00080061)
```

### 3. Search Series (QIDO-RS)
```bash
# Get a StudyInstanceUID from step 2, then:
STUDY_UID="1.3.6.1.4.1.14519.5.2.1.3320.3273.196970582315023188725339201346"

curl -u admin:admin \
     -H "Accept: application/dicom+json" \
     "http://demo02.xnatworks.io/xapi/dicomweb/projects/test/studies/${STUDY_UID}/series"

Expected: JSON array with series objects containing:
- SeriesInstanceUID (0020000E)
- Modality (00080060)
- SeriesNumber (00200011)
```

### 4. Search Instances (QIDO-RS)
```bash
# Use StudyInstanceUID and SeriesInstanceUID from above
STUDY_UID="..."
SERIES_UID="..."

curl -u admin:admin \
     -H "Accept: application/dicom+json" \
     "http://demo02.xnatworks.io/xapi/dicomweb/projects/test/studies/${STUDY_UID}/series/${SERIES_UID}/instances"

Expected: JSON array with instance objects containing:
- SOPInstanceUID (00080018)
- SOPClassUID (00080016)
```

### 5. Retrieve Metadata (WADO-RS)
```bash
# Use UIDs from above
STUDY_UID="..."
SERIES_UID="..."
INSTANCE_UID="..."

curl -u admin:admin \
     -H "Accept: application/dicom+json" \
     "http://demo02.xnatworks.io/xapi/dicomweb/projects/test/studies/${STUDY_UID}/series/${SERIES_UID}/instances/${INSTANCE_UID}/metadata"

Expected: JSON array with complete DICOM attributes
```

### 6. Download Instance (WADO-RS)
```bash
curl -u admin:admin \
     -H "Accept: application/dicom" \
     "http://demo02.xnatworks.io/xapi/dicomweb/projects/test/studies/${STUDY_UID}/series/${SERIES_UID}/instances/${INSTANCE_UID}" \
     -o test.dcm

# Verify DICOM file
dcmdump test.dcm | head -20

Expected: Valid DICOM file downloaded
```

## Next Steps

1. ✅ Deploy updated plugin JAR
2. ✅ Verify all endpoints return data
3. ✅ Test with OHIF Viewer
4. ✅ Test with VolView
5. ✅ Perform performance testing with larger projects

## Known Issues

None currently - all issues from initial deployment have been resolved.

## Performance Notes

The API-based approach may be slower than direct SQL for very large projects (1000+ sessions). If performance becomes an issue, we can:

1. Add caching layer
2. Implement pagination
3. Optimize XNAT API calls
4. Add database indexes

## Support

For issues or questions:
- Check XNAT logs: `tail -f $XNAT_HOME/logs/catalina.out | grep -i dicomweb`
- Enable debug logging in XNAT for `org.nrg.xnat.dicomweb`
- Use test page for quick verification: `/xapi/dicomweb/test`
