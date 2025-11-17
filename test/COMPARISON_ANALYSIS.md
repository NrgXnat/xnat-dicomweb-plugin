# Study Metadata Endpoint - Before vs After Comparison

## Summary
The fix changes the `/studies/{studyUID}/metadata` endpoint from returning **study-level metadata** to returning **instance-level metadata for all instances in the study**, per the DICOMweb standard.

## Before Fix (Incorrect - Current xnat_study.json)

### Structure
```json
[
  {
    "00080020": { "vr": "DA", "Value": ["19991130"] },  // Study Date
    "00080050": { "vr": "SH", "Value": ["subject1_CT_1"] },  // Accession Number
    "00100010": { "vr": "PN", "Value": [{ "Alphabetic": "XNAT_S00004" }] },  // Patient Name
    "0020000D": { "vr": "UI", "Value": ["1.3.6.1.4.1.14519..."] },  // Study Instance UID
    "00201206": { "vr": "IS", "Value": ["2"] },  // Number of Study Related Series
    "00201208": { "vr": "IS", "Value": ["44"] }  // Number of Study Related Instances
  }
]
```

### Characteristics
- **Array length**: 1
- **Content**: Study-level metadata
- **Tags**: Study-specific tags (0020000D = Study Instance UID, 00201206 = Series Count, etc.)
- **Missing**: Instance-specific tags (SOPInstanceUID, SOPClassUID, etc.)
- **Result**: Client gets summary metadata, not individual instance data

## After Fix (Correct - Matches orthanc_study.json)

### Structure
```json
[
  {
    "00080016": { "vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"] },  // SOP Class UID (CT Image)
    "00080018": { "vr": "UI", "Value": ["1.3.6.1.4.1.14519...121"] },  // SOP Instance UID #1
    "00080020": { "vr": "DA", "Value": ["19991130"] },  // Study Date
    "00080060": { "vr": "CS", "Value": ["CT"] },  // Modality
    "0020000D": { "vr": "UI", "Value": ["1.3.6.1.4.1.14519...377"] },  // Study Instance UID
    "0020000E": { "vr": "UI", "Value": ["1.3.6.1.4.1.14519...893"] },  // Series Instance UID
    "00200013": { "vr": "IS", "Value": ["1"] }  // Instance Number
    // ... more instance metadata ...
  },
  {
    "00080016": { "vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"] },  // SOP Class UID (CT Image)
    "00080018": { "vr": "UI", "Value": ["1.3.6.1.4.1.14519...456"] },  // SOP Instance UID #2
    "00080020": { "vr": "DA", "Value": ["19991130"] },  // Study Date
    // ... instance #2 metadata ...
  },
  // ... 42 more instances ...
]
```

### Characteristics
- **Array length**: 44 (number of instances in study)
- **Content**: Instance-level metadata for each DICOM instance
- **Tags**: Instance-specific tags (00080018 = SOP Instance UID, 00080016 = SOP Class UID, etc.)
- **Includes**: All series and instances in the study
- **Result**: Client gets complete metadata for every instance, can download individual files

## Key Differences

| Aspect | Before (Wrong) | After (Correct) |
|--------|---------------|-----------------|
| **Array Length** | 1 | 44 (or N instances) |
| **Represents** | Study metadata | Instance metadata |
| **SOP Instance UID** | ❌ Missing | ✅ Present (00080018) |
| **SOP Class UID** | ❌ Missing | ✅ Present (00080016) |
| **Instance Number** | ❌ Missing | ✅ Present (00200013) |
| **Series Instance UID** | ❌ Missing | ✅ Present (0020000E) |
| **Modality** | Multiple (summary) | Specific per instance |
| **Use Case** | Study summary | WADO-RS retrieval |

## DICOMweb Standard Compliance

Per **DICOM PS3.18 Section 6.5.6** (WADO-RS RetrieveMetadata):

> "The origin server shall return in the message body the DICOM JSON Model of the instances within the Study"

### What This Means
- **Not**: "Return metadata ABOUT the study"
- **But**: "Return metadata FOR each instance IN the study"

The standard expects clients to:
1. Call `/studies/{uid}/metadata` to get all instance metadata
2. Parse the array to find instances of interest
3. Use SOP Instance UIDs to retrieve actual DICOM files via `/studies/{uid}/series/{series}/instances/{instance}`

## Impact of Fix

### HTTP 406 Error - Root Cause
The 406 (Not Acceptable) error likely occurred because:
1. Client expected an array of instance objects with SOP Instance UIDs
2. XNAT returned an array with 1 study object (no SOP Instance UIDs)
3. Client couldn't parse/validate the response structure
4. Content negotiation failed → 406 error

### After Fix
- ✅ Returns array of N instances (matching Orthanc)
- ✅ Each object has SOP Instance UID (00080018)
- ✅ Each object has SOP Class UID (00080016) 
- ✅ Clients can map to WADO-RS retrieve endpoints
- ✅ Compatible with DICOMweb viewers (OHIF, Cornerstone, etc.)

## Code Change Summary

### Before
```java
Attributes attrs = dicomService.retrieveStudyMetadata(user, projectId, studyUID);
String json = "[" + DicomWebUtils.toJson(attrs) + "]";
```
Returns: `[{study metadata}]`

### After
```java
List<Attributes> instances = dicomService.searchInstances(user, projectId, studyUID, null, null);
String json = "[" + instances.stream()
    .map(attrs -> DicomWebUtils.toJson(attrs))
    .collect(Collectors.joining(",")) + "]";
```
Returns: `[{instance1}, {instance2}, ..., {instanceN}]`

## Testing

To verify the fix works:

1. **Deploy plugin** to XNAT instance
2. **Call endpoint**: `GET /dicomweb/projects/{project}/studies/{studyUID}/metadata`
3. **Verify response**:
   - Array length = number of instances (not 1)
   - Each object has `00080018` (SOP Instance UID)
   - Each object has `00080016` (SOP Class UID)
   - Response matches structure in `orthanc_study.json`

4. **Test with DICOMweb viewer**:
   - OHIF Viewer or similar should now load study correctly
   - No more 406 errors
   - Can view/navigate all series and instances
