# XNAT DICOMweb Plugin - User Guide

**Version:** 1.1.3
**Last Updated:** December 11, 2025

## Table of Contents

- [Introduction](#introduction)
- [What is DICOMweb?](#what-is-dicomweb)
- [Getting Started](#getting-started)
- [Installation](#installation)
- [Configuration](#configuration)
- [Searching for Studies (QIDO-RS)](#searching-for-studies-qido-rs)
- [Retrieving Images (WADO-RS)](#retrieving-images-wado-rs)
- [Uploading Images (STOW-RS)](#uploading-images-stow-rs)
- [Using with DICOM Viewers](#using-with-dicom-viewers)
- [Authentication](#authentication)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)
- [FAQ](#faq)

---

## Introduction

The XNAT DICOMweb Plugin enables your XNAT server to communicate with modern DICOM viewers and applications using the DICOMweb standard. This allows you to:

- Browse XNAT imaging data in web-based DICOM viewers (OHIF, VolView)
- Search for studies using standard DICOMweb queries
- Retrieve images and metadata via RESTful APIs
- Upload DICOM files from any DICOMweb-compliant client

**Who is this guide for?**
- XNAT users who want to view their data in modern DICOM viewers
- Developers integrating XNAT with DICOMweb applications
- System administrators configuring DICOMweb access

---

## What is DICOMweb?

DICOMweb is a modern web standard for medical imaging that uses:
- **RESTful APIs** - Standard HTTP GET/POST requests
- **JSON/XML** - Web-friendly data formats
- **HTTPS** - Secure communication

It replaces older DICOM protocols (C-FIND, C-MOVE) with web-based equivalents:

| Traditional DICOM | DICOMweb | What it does |
|-------------------|----------|--------------|
| C-FIND | QIDO-RS | Search for studies |
| C-GET/C-MOVE | WADO-RS | Retrieve images |
| C-STORE | STOW-RS | Upload images |

**Benefits:**
- Works through firewalls (uses HTTP/HTTPS)
- Compatible with web browsers and JavaScript
- No special DICOM networking required
- Supports modern authentication (OAuth, JWT)

---

## Getting Started

### Prerequisites

Before using the DICOMweb plugin, ensure you have:

1. **XNAT Installation**
   - XNAT version 1.9.0 or higher
   - Access to XNAT admin interface
   - Project with DICOM data uploaded

2. **User Permissions**
   - Read access to view studies (QIDO-RS, WADO-RS)
   - Edit access to upload studies (STOW-RS)

3. **DICOM Data Requirements**
   - DICOM files must have valid UIDs:
     - StudyInstanceUID
     - SeriesInstanceUID
     - SOPInstanceUID
   - Files stored in XNAT archive or prearchive

### Quick Test

After installation, test the plugin with a simple query:

```bash
# Replace with your XNAT URL and credentials
curl -u username:password \
  "https://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT/studies" \
  -H "Accept: application/dicom+json"
```

If successful, you'll see a JSON array of studies.

---

## Installation

### For System Administrators

**1. Build the Plugin:**

```bash
cd /path/to/xnat_dicomweb_plugin
./gradlew clean xnatPluginJar
```

This creates: `build/libs/xnat-dicomweb-plugin-1.1.3-xpl.jar`

**2. Deploy to XNAT:**

```bash
# Copy to XNAT plugins directory
cp build/libs/xnat-dicomweb-plugin-1.1.3-xpl.jar /path/to/xnat-home/plugins/

# Example for Tomcat deployment
cp build/libs/xnat-dicomweb-plugin-1.1.3-xpl.jar ~/xnathome/plugins/
```

**3. Restart XNAT:**

```bash
# Tomcat example
/opt/tomcat/bin/shutdown.sh
/opt/tomcat/bin/startup.sh

# Or using systemd
sudo systemctl restart tomcat9
```

**4. Verify Installation:**

Check XNAT logs for:
```
INFO - Loading plugin: xnat-dicomweb-plugin version 1.1.3
INFO - Registered DICOMweb endpoints
```

Access the admin UI:
```
Administer → Plugin Settings → DICOMweb Plugin
```

---

## Configuration

The plugin works out-of-the-box with sensible defaults. Configuration is optional.

### Via Admin UI (Recommended)

1. Log in to XNAT as administrator
2. Go to **Administer → Plugin Settings → DICOMweb Plugin Configuration**
3. Adjust settings as needed
4. Click **Save**

Changes take effect immediately (no restart required).

### Configuration Options

#### 1. Pagination Settings

Controls how many results are returned per page when searching:

| Setting | Default | Recommended Range | Description |
|---------|---------|-------------------|-------------|
| **Default Page Size** | 100 | 50-200 | Results returned when no limit specified |
| **Max Page Size** | 1000 | 500-2000 | Maximum allowed limit (prevents abuse) |

**When to adjust:**
- **High-performance network**: Increase to 200-500 for faster browsing
- **Slow network/limited memory**: Decrease to 50-100 for better responsiveness
- **Public-facing server**: Keep max at 1000 to prevent resource exhaustion

**Example Query:**
```bash
# Get first 50 studies
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies?limit=50&offset=0"
```

#### 2. Memory Threshold

Controls when uploaded files are written to disk vs. kept in memory:

| Setting | Default | Recommended |
|---------|---------|-------------|
| **Memory Threshold** | 10 MB | 5-50 MB |

**Guidelines:**
- **High-memory server (16GB+)**: 50 MB - faster uploads
- **Low-memory server (<8GB)**: 5 MB - prevents out-of-memory errors
- **Default (10 MB)**: Good balance for most deployments

#### 3. Bulk Data Threshold

Controls when DICOM attributes use URIs instead of inline values:

| Setting | Default | Description |
|---------|---------|-------------|
| **Bulk Data Threshold** | 1 KB | Attributes larger than this use BulkDataURI |

**Effect on metadata responses:**

**Small threshold (512-1024 bytes):**
```json
{
  "7FE00010": {
    "vr": "OB",
    "BulkDataURI": "http://.../bulkdata/7FE00010"
  }
}
```
- Smaller JSON responses
- Separate requests needed for pixel data
- Better for slow networks

**Large threshold (4096-8192 bytes):**
```json
{
  "7FE00010": {
    "vr": "OB",
    "InlineBinary": "base64encodeddata..."
  }
}
```
- Larger JSON responses
- Everything in one request
- Better for fast networks

### Initial Configuration File

**Initial defaults** are defined in the plugin's properties file at first installation:

**File:** `src/main/resources/config/dicomweb/dicomweb.properties` (plugin source)

```properties
# Default configuration (used only on first load)
dicomweb.defaultPageSize=100
dicomweb.maxPageSize=1000
dicomweb.memoryThreshold=10485760
dicomweb.bulkDataThreshold=1024
```

**Important Notes:**
- ⚠️ **Configuration is stored in the database** after first load
- ⚠️ **Modifying the properties file will NOT affect running instances**
- ✅ **Use Admin UI to modify settings** - changes take effect immediately
- ⚠️ Properties file is only used for initial defaults when plugin is first installed

**To reset to defaults:**
1. Go to Admin UI → Plugin Settings → DICOMweb Plugin
2. Manually enter default values shown above
3. Click Save

---

## Searching for Studies (QIDO-RS)

QIDO-RS (Query based on ID for DICOM Objects over RESTful Services) lets you search for studies, series, and instances.

### Basic Search

**Search all studies in a project:**

```bash
curl -u username:password \
  "https://your-xnat-server/xapi/dicomweb/projects/MyProject/studies" \
  -H "Accept: application/dicom+json"
```

**Response:**
```json
[
  {
    "00100010": {"vr": "PN", "Value": [{"Alphabetic": "DOE^JOHN"}]},
    "00100020": {"vr": "LO", "Value": ["P12345"]},
    "0020000D": {"vr": "UI", "Value": ["1.2.840.113619..."]},
    "00080020": {"vr": "DA", "Value": ["20241201"]},
    "00080060": {"vr": "CS", "Value": ["CT"]}
  }
]
```

### Filtering Studies

Add query parameters to filter results:

**By Patient Name:**
```bash
# Exact match
?PatientName=DOE^JOHN

# Wildcard matching
?PatientName=DOE*        # All patients starting with "DOE"
?PatientName=*SMITH*     # All patients containing "SMITH"
?PatientName=DOE^J?HN    # ? matches single character
```

**By Modality:**
```bash
?Modality=CT             # All CT studies
?Modality=MR             # All MR studies
```

**By Date Range:**
```bash
?StudyDate=20241201                    # Specific date
?StudyDate=20240101-20241231          # Date range (all of 2024)
?StudyDate=20240101-                  # On or after Jan 1
?StudyDate=-20241231                  # On or before Dec 31
```

Dates use the DICOM `YYYYMMDD` format. A malformed date returns
**400 Bad Request** rather than an empty result list:

```bash
?StudyDate=2024-12-01                 # 400 — use 20241201; a hyphen
                                      #       means a range, not a separator
?StudyDate=20241345                   # 400 — no month 13
?StudyDate=2024*                      # 400 — wildcards work on names,
                                      #       not on dates or times
```

**By Patient ID:**
```bash
?PatientID=P12345
```

**Combining Filters:**
```bash
# CT studies for patient DOE from December 2024
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies?PatientName=DOE*&Modality=CT&StudyDate=20241201-20241231"
```

### Pagination

**Using limit and offset:**

```bash
# First 50 studies
?limit=50&offset=0

# Next 50 studies
?limit=50&offset=50

# Studies 101-150
?limit=50&offset=100
```

**Check total count:**

The `X-Total-Count` header tells you how many total results match:

```bash
curl -I -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies?Modality=CT"

# Response header:
# X-Total-Count: 247
```

### Searching Series

**All series in a study:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies/1.2.840.113619.../series"
```

**Filter by modality:**
```bash
?Modality=CT
```

**Filter by series description:**
```bash
?SeriesDescription=*HEAD*              # Contains "HEAD"
?SeriesDescription=CT%20BRAIN*         # Starts with "CT BRAIN"
```

### Searching Instances

**All instances in a series:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies/1.2.840.../series/1.2.840.../instances"
```

### Supported Query Parameters

| Parameter | Applies To | Example | Wildcard Support |
|-----------|------------|---------|------------------|
| `PatientName` | Studies | `DOE^JOHN` | Yes (`*`, `?`) |
| `PatientID` | Studies | `P12345` | No |
| `StudyDate` | Studies | `20241201-20241231` | No — range supported; malformed values return 400 |
| `StudyTime` | Studies | `080000-170000` | No — range supported; malformed values return 400 |
| `StudyInstanceUID` | Studies | `1.2.840...` | No |
| `AccessionNumber` | Studies | `ACC123` | No |
| `Modality` | Studies, Series | `CT` | No |
| `SeriesDescription` | Series | `CT HEAD` | Yes |
| `SeriesInstanceUID` | Series | `1.2.840...` | No |
| `SeriesNumber` | Series | `4` | No |
| `SOPInstanceUID` | Instances | `1.2.840...` | No |
| `SOPClassUID` | Instances | `1.2.840.10008...` | No |
| `InstanceNumber` | Instances | `1` | No |

**For detailed QIDO-RS documentation, see:** [docs/QIDO_RS_IMPLEMENTATION.md](QIDO_RS_IMPLEMENTATION.md)

---

## Retrieving Images (WADO-RS)

WADO-RS (Web Access to DICOM Objects over RESTful Services) lets you download DICOM files and metadata.

### Retrieving DICOM Files

**Single instance:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies/1.2.3.../series/1.2.4.../instances/1.2.5..." \
  -o image.dcm
```

**All instances in a series:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies/1.2.3.../series/1.2.4..." \
  -H "Accept: multipart/related; type=\"application/dicom\"" \
  -o series.multipart
```

**All instances in a study:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies/1.2.3..." \
  -H "Accept: multipart/related; type=\"application/dicom\"" \
  -o study.multipart
```

### Retrieving Metadata

**Instance metadata (JSON):**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies/1.2.3.../series/1.2.4.../instances/1.2.5.../metadata" \
  -H "Accept: application/dicom+json"
```

**Response:**
```json
[
  {
    "00080016": {"vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"]},
    "00080018": {"vr": "UI", "Value": ["1.2.840.113619..."]},
    "00100010": {"vr": "PN", "Value": [{"Alphabetic": "DOE^JOHN"}]},
    "00280010": {"vr": "US", "Value": [512]},
    "00280011": {"vr": "US", "Value": [512]},
    "7FE00010": {
      "vr": "OB",
      "BulkDataURI": "http://xnat/.../bulkdata/7FE00010"
    }
  }
]
```

**Series metadata:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies/1.2.3.../series/1.2.4.../metadata"
```

Returns metadata for all instances in the series.

**Study metadata:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies/1.2.3.../metadata"
```

Returns metadata for all instances in the study.

### Retrieving Rendered Images

Get images as JPEG/PNG/GIF without needing a DICOM parser:

**As JPEG:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/studies/1.2.3.../series/1.2.4.../instances/1.2.5.../rendered" \
  -H "Accept: image/jpeg" \
  -o image.jpg
```

**As PNG:**
```bash
-H "Accept: image/png" -o image.png
```

**Multi-frame images:**

For multi-frame DICOM (e.g., cine, PET):

```bash
# Get specific frame
?frame=5

# Get middle frame (default)
# (no frame parameter)

# Get as animated GIF
-H "Accept: image/gif" -o animation.gif
```

**Response headers:**
```
Content-Type: image/jpeg
X-Frame-Count: 89
X-Frame-Number: 45
X-Frame-Rate: 30.00
X-Multi-Frame: true
```

### Retrieving Individual Frames

For efficient multi-frame image access:

**Single frame:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/.../instances/1.2.5.../frames/1" \
  -o frame1.raw
```

**Multiple frames:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/.../instances/1.2.5.../frames/1,3,5" \
  -o frames.multipart
```

Response is `multipart/related` with separate parts for each frame.

### Retrieving Bulk Data

Get raw binary data for specific DICOM attributes:

**Pixel data:**
```bash
curl -u user:pass \
  "https://xnat/xapi/dicomweb/projects/MyProject/.../instances/1.2.5.../bulkdata/7FE00010" \
  -o pixeldata.raw
```

Common bulk data tags:
- `7FE00010` - PixelData
- `7FE00020` - FloatPixelData
- `7FE00030` - DoubleFloatPixelData

### Compression Format Support

| Format | Support | Requirements |
|--------|---------|--------------|
| Uncompressed | ✅ Full | None |
| JPEG Baseline | ✅ Full | None |
| JPEG Extended | ✅ Full | None |
| JPEG Lossless | ✅ Full | None |
| RLE Lossless | ✅ Full | None |
| JPEG-LS | ⚠️ Requires OpenCV | Native libraries |
| JPEG 2000 | ⚠️ Requires OpenCV | Native libraries |

**If advanced formats fail:**

The plugin returns HTTP 501 with installation instructions:
```json
{
  "error": "UnsupportedOperation",
  "message": "Cannot render image. This DICOM file uses compression formats (JPEG-LS or JPEG 2000) that require OpenCV native libraries. Install OpenCV (macOS: 'brew install opencv', Ubuntu: 'apt-get install libopencv-dev') or use the retrieveInstance endpoint to download the original DICOM file.",
  "status": 501
}
```

**Solution:** Install OpenCV or download the original DICOM file.

**For detailed WADO-RS documentation, see:** [docs/WADO_RS_IMPLEMENTATION.md](WADO_RS_IMPLEMENTATION.md)

---

## Uploading Images (STOW-RS)

STOW-RS (STore Over the Web by RESTful Services) lets you upload DICOM files to XNAT.

### Basic Upload

**Upload single DICOM file:**

```bash
# Create multipart request
BOUNDARY="boundary123"
DICOM_FILE="image.dcm"
PROJECT_ID="MyProject"

curl -u user:pass -X POST \
  -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=$BOUNDARY" \
  -H "Accept: application/dicom+json" \
  --data-binary @- \
  "https://xnat/xapi/dicomweb/projects/$PROJECT_ID/studies" << EOF
--$BOUNDARY
Content-Type: application/dicom

$(cat "$DICOM_FILE")
--$BOUNDARY--
EOF
```

**Important:** The `type` parameter value **must be quoted** (`type="application/dicom"`) because it contains the `/` character, which is a special character (tspecial) per RFC 2045. Unquoted format will result in HTTP 415 error.

**Response:**
```json
{
  "00081190": {"vr": "UR", "Value": ["http://xnat/..."]},
  "00081198": {
    "vr": "SQ",
    "Value": [
      {
        "00081150": {"vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"]},
        "00081155": {"vr": "UI", "Value": ["1.2.840.113619..."]},
        "00081190": {"vr": "UR", "Value": ["http://xnat/..."]}
      }
    ]
  }
}
```

### Upload Multiple Files

```bash
# Multiple files in single request
--boundary123
Content-Type: application/dicom

[DICOM file 1 binary data]
--boundary123
Content-Type: application/dicom

[DICOM file 2 binary data]
--boundary123
Content-Type: application/dicom

[DICOM file 3 binary data]
--boundary123--
```

### Import Strategies

STOW-RS supports two import strategies:

#### 1. GradualDicomImporter (Default)

Uses XNAT's standard import pipeline:

```bash
# Default strategy (no parameter needed)
POST /xapi/dicomweb/projects/MyProject/studies
```

**Features:**
- Full DICOM validation
- Automatic session/scan creation
- Standard XNAT workflow
- Triggers XNAT automation scripts

**Best for:**
- Production uploads
- Clinical data
- When you need full validation

#### 2. DirectWrite

Writes directly to prearchive with automatic registration:

```bash
# Specify DirectWrite strategy
POST /xapi/dicomweb/projects/MyProject/studies?strategy=DirectWrite
```

**Features:**
- Faster uploads
- Direct file write to prearchive
- Automatic PrearcDatabase registration
- Sessions immediately visible in UI

**Best for:**
- Batch uploads
- High-volume imports
- Research data

### Error Handling

STOW-RS supports partial success - some files can succeed while others fail:

**Mixed success/failure response:**
```json
{
  "00081198": {
    "vr": "SQ",
    "Value": [
      {
        "00081150": {"vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"]},
        "00081155": {"vr": "UI", "Value": ["1.2.840.113619..."]},
        "00081190": {"vr": "UR", "Value": ["http://xnat/..."]}
      }
    ]
  },
  "00081197": {
    "vr": "SQ",
    "Value": [
      {
        "00081150": {"vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"]},
        "00081155": {"vr": "UI", "Value": ["1.2.840.113619..."]},
        "00081197": {"vr": "US", "Value": [43264]},
        "00081198": {"vr": "LO", "Value": ["Invalid DICOM file"]}
      }
    ]
  }
}
```

- `00081198` (ReferencedSOPSequence) - Successfully stored instances
- `00081197` (FailedSOPSequence) - Failed instances with reason codes

### Python Example

Using `requests` library:

```python
import requests
from requests.auth import HTTPBasicAuth

url = "https://xnat/xapi/dicomweb/projects/MyProject/studies"
auth = HTTPBasicAuth("username", "password")

# Read DICOM file
with open("image.dcm", "rb") as f:
    dicom_data = f.read()

# Create multipart request
boundary = "boundary123"
body = (
    f"--{boundary}\r\n"
    f"Content-Type: application/dicom\r\n\r\n"
).encode() + dicom_data + f"\r\n--{boundary}--\r\n".encode()

headers = {
    "Content-Type": f'multipart/related; type="application/dicom"; boundary={boundary}',
    "Accept": "application/dicom+json"
}

response = requests.post(url, auth=auth, headers=headers, data=body)
print(response.json())
```

**Note:** Use single quotes for the outer string and double quotes for `"application/dicom"` to ensure proper RFC 2045 compliance.

**For detailed STOW-RS documentation, see:** [docs/STOW_RS_IMPLEMENTATION_PLAN.md](STOW_RS_IMPLEMENTATION_PLAN.md)

---

## Using with DICOM Viewers

### OHIF Viewer

OHIF is a zero-footprint web-based medical image viewer.

#### Quick Start with Docker

**1. Create OHIF configuration:**

```bash
mkdir -p config
cat > config/default.js << 'EOF'
window.config = {
  routerBasename: '/',
  showStudyList: true,
  dataSources: [
    {
      namespace: '@ohif/extension-default.dataSourcesModule.dicomweb',
      sourceName: 'dicomweb',
      configuration: {
        friendlyName: 'XNAT DICOMweb',
        name: 'XNAT',
        wadoUriRoot: 'https://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT',
        qidoRoot: 'https://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT',
        wadoRoot: 'https://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT',
        imageRendering: 'wadors',
        thumbnailRendering: 'wadors',
      },
    },
  ],
};
EOF
```

**Replace:**
- `your-xnat-server` with your XNAT URL
- `YOUR_PROJECT` with your project ID

**2. Run OHIF:**

```bash
docker run -d \
  --name ohif-viewer \
  -p 3000:80 \
  -v $(pwd)/config/default.js:/usr/share/nginx/html/app-config.js \
  ohif/app:latest
```

**3. Access OHIF:**

Open browser to: `http://localhost:3000`

#### Authentication

**Option 1: Basic Auth in Config**

```javascript
configuration: {
  // ... other settings
  headers: {
    Authorization: 'Basic ' + btoa('username:password')
  }
}
```

**Option 2: Session Cookie**

1. Log in to XNAT in one browser tab
2. Open OHIF in another tab (same browser)
3. OHIF will use the session cookie

**Option 3: API Token**

Create XNAT alias token and use in config.

#### Troubleshooting OHIF

**CORS Errors:**

Add to XNAT nginx config:
```nginx
location /xnat/xapi/dicomweb/ {
    add_header 'Access-Control-Allow-Origin' '*';
    add_header 'Access-Control-Allow-Methods' 'GET, POST, OPTIONS';
    add_header 'Access-Control-Allow-Headers' 'Authorization, Content-Type';

    if ($request_method = 'OPTIONS') {
        return 204;
    }

    proxy_pass http://localhost:8080/xnat/xapi/dicomweb/;
}
```

**Network Tab Shows 404:**

- Verify plugin installed: Check XNAT admin → Plugins
- Check endpoint works: `curl -u user:pass https://xnat/xapi/dicomweb/projects/test/studies`

**For detailed OHIF testing guide, see:** [docs/TESTING_OHIF.md](TESTING_OHIF.md)

---

### VolView

VolView is a desktop/web medical image viewer with advanced 3D capabilities.

**1. Open VolView**

**2. Go to File → Remote**

**3. Enter DICOMweb URL:**
```
https://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT
```

**4. Authenticate:**

Enter your XNAT username and password

**5. Browse and Load:**

- Browse available studies
- Select study to load
- VolView downloads and displays images

---

### Other DICOMweb Clients

The plugin is compatible with any DICOMweb-compliant client:

- **Cornerstone.js** - JavaScript medical imaging library
- **Orthanc** - Medical PACS server
- **dcm4che** - DICOM toolkit
- **pydicom** - Python DICOM library (with pynetdicom)

**Generic configuration:**
```
QIDO-RS Root: https://your-xnat-server/xapi/dicomweb/projects/{project}
WADO-RS Root: https://your-xnat-server/xapi/dicomweb/projects/{project}
STOW-RS Root: https://your-xnat-server/xapi/dicomweb/projects/{project}
```

---

## Authentication

All DICOMweb endpoints require XNAT authentication.

### Methods

**1. HTTP Basic Authentication:**
```bash
curl -u username:password https://xnat/xapi/dicomweb/...
```

**2. Session Cookie:**

Log in to XNAT web UI first, then API calls use the session.

**3. Authorization Header:**
```bash
curl -H "Authorization: Basic $(echo -n 'user:pass' | base64)" \
  https://xnat/xapi/dicomweb/...
```

**4. XNAT Alias Token:**

Create alias token in XNAT:
1. User → Edit Profile → Manage Alias Tokens
2. Create new token
3. Use as password in Basic Auth

### Permissions

| Operation | Required Permission |
|-----------|---------------------|
| QIDO-RS (Search) | Read access to project |
| WADO-RS (Retrieve) | Read access to project |
| STOW-RS (Upload) | Edit access to project |

**Check your access:**

Go to XNAT → Browse → Projects → [Your Project] → Access

---

## Troubleshooting

### Common Issues

#### 1. 404 Not Found

**Problem:** Endpoints return 404

**Possible Causes:**
- Plugin not installed
- Wrong URL
- Project doesn't exist

**Solutions:**
```bash
# Check plugin installed
ls /path/to/xnat-home/plugins/ | grep dicomweb

# Check XNAT logs
tail -f /path/to/xnat-home/logs/xapi.log | grep -i dicomweb

# Verify endpoint exists
curl -I -u user:pass https://xnat/xapi/dicomweb/projects/test/studies
```

#### 2. 401 Unauthorized

**Problem:** Authentication fails

**Solutions:**
- Verify credentials: Log in to XNAT web UI with same credentials
- Check for special characters in password (URL-encode if needed)
- Try creating an alias token

#### 3. 403 Forbidden

**Problem:** Authenticated but access denied

**Cause:** No read/edit permission on project

**Solution:** Contact project owner to grant access

#### 4. Empty Results

**Problem:** Query returns `[]` but you know data exists

**Possible Causes:**
- DICOM files missing StudyInstanceUID
- Files not in archive/prearchive
- Wrong project ID

**Debug:**
```bash
# Check session in XNAT
# Browse → Projects → [Project] → Subjects

# Check DICOM files have UIDs
dcmdump /path/to/file.dcm | grep StudyInstanceUID
```

#### 5. Slow Performance

**Problem:** Queries take a long time

**Solutions:**
- Reduce page size: `?limit=50`
- Use specific UIDs when known: `?StudyInstanceUID=1.2.3...`
- Filter early: `?Modality=CT&StudyDate=20241201`
- Check XNAT server resources

#### 6. CORS Errors in Browser

**Problem:** Browser console shows CORS policy errors

**Solution:** Configure CORS in nginx (see OHIF section above)

#### 7. Rendered Images Fail

**Problem:** `/rendered` endpoint returns 501

**Cause:** DICOM uses JPEG-LS or JPEG 2000 compression, OpenCV not installed

**Solutions:**
1. Install OpenCV:
   ```bash
   # macOS
   brew install opencv

   # Ubuntu
   sudo apt-get install libopencv-dev

   # CentOS
   sudo yum install opencv-devel
   ```
   Then restart XNAT.

2. Or download original DICOM instead:
   ```bash
   # Use retrieveInstance instead of rendered
   curl -u user:pass \
     "https://xnat/.../instances/1.2.3..." \
     -o original.dcm
   ```

### Getting Help

**1. Check Logs:**

XNAT logs:
```bash
tail -f /path/to/xnat-home/logs/xapi.log
```

Look for:
- `ERROR` lines
- `org.nrg.xnat.dicomweb` package messages

**2. Enable Debug Logging:**

Edit `log4j.properties`:
```properties
log4j.logger.org.nrg.xnat.dicomweb=DEBUG
```

Restart XNAT, then check logs for detailed traces.

**3. Test with curl:**

Isolate issues by testing with curl before using clients:
```bash
# Test QIDO-RS
curl -v -u user:pass \
  "https://xnat/xapi/dicomweb/projects/test/studies" \
  -H "Accept: application/dicom+json"

# Test WADO-RS
curl -v -u user:pass \
  "https://xnat/xapi/dicomweb/projects/test/studies/1.2.3.../metadata"
```

**4. Check DICOMweb Conformance:**

See [docs/DICOMWEB_CONFORMANCE.md](DICOMWEB_CONFORMANCE.md) for standards compliance details.

---

## Best Practices

### For Users

**1. Use Pagination:**

Don't request all results at once:
```bash
# Good - paginated
?limit=100&offset=0

# Bad - could be thousands of results
# (no limit)
```

**2. Filter Early:**

Narrow down results with query parameters:
```bash
# Good - filtered query
?Modality=CT&StudyDate=20241201&limit=50

# Bad - filter client-side after downloading all results
?limit=10000
```

**3. Use Specific UIDs:**

When you know the UID, use it:
```bash
# Fast - direct lookup
?StudyInstanceUID=1.2.840.113619...

# Slower - requires file reads
?PatientName=DOE*
```

**4. Prefer Rendered Endpoint for Previews:**

For thumbnails and previews:
```bash
# Good - small JPEG
GET .../instances/1.2.3.../rendered
# Returns ~50KB

# Bad - full DICOM
GET .../instances/1.2.3...
# Returns ~500KB
```

### For Administrators

**1. Monitor Resource Usage:**

Watch for:
- High memory usage during uploads
- Slow disk I/O during queries
- Many concurrent connections

**2. Tune Configuration:**

Adjust based on deployment:
- **Public server**: Lower limits, smaller thresholds
- **Internal network**: Higher limits, larger thresholds

**3. Enable HTTPS:**

Always use HTTPS in production for:
- Secure authentication
- HIPAA/GDPR compliance
- Patient data protection

**4. Set Up Monitoring:**

Monitor XNAT logs for:
- Failed uploads
- Slow queries
- Authentication failures

**5. Regular Backups:**

Ensure XNAT archive and database are backed up regularly.

---

## FAQ

### General

**Q: Do I need to configure anything to start using DICOMweb?**

A: No, the plugin works out-of-the-box with sensible defaults. Configuration is optional for tuning performance.

**Q: Can multiple users upload to the same project simultaneously?**

A: Yes, STOW-RS is thread-safe and supports concurrent uploads.

**Q: Does this replace XNAT's DICOM receiver?**

A: No, it complements it. Traditional DICOM C-STORE still works. This adds web-based access.

### QIDO-RS

**Q: Why do I get empty results when I know data exists?**

A: Check that:
1. DICOM files have StudyInstanceUID
2. You have read access to the project
3. Data is in archive or prearchive (not just uploaded to XNAT)

**Q: What's the maximum number of results I can retrieve?**

A: Controlled by `maxPageSize` config (default: 1000). Use pagination for larger result sets.

**Q: Can I search across multiple projects?**

A: Not currently. Each query is scoped to a single project: `/projects/{projectId}/studies`

### WADO-RS

**Q: Why is BulkDataURI used instead of inline pixel data?**

A: For efficiency. Pixel data can be megabytes - using URIs keeps JSON responses small. Fetch pixel data separately if needed.

**Q: Can I download a whole study at once?**

A: Yes:
```bash
GET /projects/{proj}/studies/{studyUID}
Accept: multipart/related; type="application/dicom"
```

**Q: What image formats are supported for rendering?**

A: JPEG, PNG, and GIF (including animated GIF for multi-frame images).

### STOW-RS

**Q: Which import strategy should I use?**

A:
- **GradualDicomImporter**: Production uploads, clinical data, need validation
- **DirectWrite**: Batch uploads, research data, need speed

**Q: What happens if my upload partially fails?**

A: STOW-RS supports partial success. The response indicates which instances succeeded and which failed.

**Q: Can I upload non-DICOM files?**

A: No, only valid DICOM files are accepted.

### Viewers

**Q: Does OHIF work with all XNAT projects?**

A: Yes, as long as you have read access and the project contains DICOM data with valid UIDs.

**Q: Why can't OHIF load my multi-frame images?**

A: Ensure the plugin is version 1.1.3+ which includes frame retrieval support.

**Q: Can I use multiple viewers simultaneously?**

A: Yes, DICOMweb is stateless. Multiple viewers can access the same data concurrently.

---

## Quick Reference

### Endpoint Summary

| Operation | Method | Endpoint Pattern |
|-----------|--------|------------------|
| **Search studies** | GET | `/projects/{proj}/studies` |
| **Search series** | GET | `/projects/{proj}/studies/{study}/series` |
| **Search instances** | GET | `/projects/{proj}/studies/{study}/series/{series}/instances` |
| **Retrieve instance** | GET | `/projects/{proj}/studies/{study}/series/{series}/instances/{instance}` |
| **Retrieve metadata** | GET | `/projects/{proj}/studies/{study}/metadata` |
| **Retrieve rendered** | GET | `/projects/{proj}/studies/{study}/series/{series}/instances/{instance}/rendered` |
| **Retrieve frames** | GET | `/projects/{proj}/.../instances/{instance}/frames/{frameList}` |
| **Retrieve bulk data** | GET | `/projects/{proj}/.../instances/{instance}/bulkdata/{tag}` |
| **Upload instances** | POST | `/projects/{proj}/studies[?strategy={strategy}]` |

### HTTP Status Codes

| Code | Meaning | Common Causes |
|------|---------|---------------|
| 200 | Success | Request successful |
| 400 | Bad Request | Invalid parameter format |
| 401 | Unauthorized | Not authenticated |
| 403 | Forbidden | No permission on project |
| 404 | Not Found | Project/study/series/instance doesn't exist |
| 500 | Internal Error | Server error, check logs |
| 501 | Not Implemented | Missing codec (e.g., OpenCV for JPEG-LS) |

### Common curl Examples

```bash
# Search all studies
curl -u user:pass "https://xnat/xapi/dicomweb/projects/test/studies"

# Search with filter and pagination
curl -u user:pass "https://xnat/xapi/dicomweb/projects/test/studies?Modality=CT&limit=50"

# Get metadata
curl -u user:pass "https://xnat/xapi/dicomweb/projects/test/studies/1.2.3.../metadata"

# Download DICOM file
curl -u user:pass "https://xnat/xapi/dicomweb/projects/test/.../instances/1.2.3..." -o file.dcm

# Get rendered JPEG
curl -u user:pass "https://xnat/xapi/dicomweb/projects/test/.../rendered" -o image.jpg
```

---

## Additional Resources

### Documentation

- **QIDO-RS Implementation:** [docs/QIDO_RS_IMPLEMENTATION.md](QIDO_RS_IMPLEMENTATION.md)
- **WADO-RS Implementation:** [docs/WADO_RS_IMPLEMENTATION.md](WADO_RS_IMPLEMENTATION.md)
- **STOW-RS Implementation:** [docs/STOW_RS_IMPLEMENTATION_PLAN.md](STOW_RS_IMPLEMENTATION_PLAN.md)
- **DICOMweb Conformance:** [docs/DICOMWEB_CONFORMANCE.md](DICOMWEB_CONFORMANCE.md)
- **Testing with OHIF:** [docs/TESTING_OHIF.md](TESTING_OHIF.md)

### External Resources

- **DICOM Standard:** https://www.dicomstandard.org/
- **DICOM PS3.18 (Web Services):** https://dicom.nema.org/medical/dicom/current/output/html/part18.html
- **DICOMweb Overview:** https://www.dicomstandard.org/using/dicomweb
- **XNAT Documentation:** https://wiki.xnat.org/
- **OHIF Viewer:** https://ohif.org/
- **VolView:** https://volview.kitware.com/

### Support

- **GitHub Repository:** [Link to your repository]
- **Issue Tracker:** [Link to issue tracker]
- **XNAT Forum:** https://groups.google.com/g/xnat_discussion

---

## Changelog

### Version 1.1.3 (December 2025)

- ✅ Complete QIDO-RS implementation with pagination
- ✅ Full WADO-RS support including rendered images and frames
- ✅ STOW-RS with two import strategies
- ✅ Admin UI for configuration
- ✅ Multi-frame image support (including animated GIF)
- ✅ BulkDataURI substitution
- ✅ DICOMweb PS3.18 conformance
- ✅ OHIF and VolView integration

---

**For technical details and development information, see [CLAUDE.md](../CLAUDE.md)**

---

*This user guide is maintained as part of the XNAT DICOMweb Plugin. For corrections or additions, please submit an issue or pull request.*
