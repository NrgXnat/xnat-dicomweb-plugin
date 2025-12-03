# XNAT DICOMweb Proxy Plugin

This XNAT plugin provides a DICOMweb-compliant REST API that exposes XNAT projects as DICOMweb endpoints. This enables DICOM viewers like OHIF and VolView to connect to XNAT and browse/view DICOM data.

## Features

- **QIDO-RS (Query)**: Search for studies, series, and instances
- **WADO-RS (Retrieve)**: Retrieve DICOM instances and metadata
- **STOW-RS (Store)**: Upload DICOM instances to XNAT
- Full support for DICOM JSON format
- CORS enabled for web viewer integration
- XNAT authentication and authorization

## Installation

1. Build the plugin:
   ```bash
   ./gradlew jar
   ```

2. Copy the generated JAR file from `build/libs/` to your XNAT plugins directory:
   ```bash
   cp build/libs/xnat-dicomweb-proxy-1.1.2.jar /path/to/xnat/plugins/
   ```

3. Restart XNAT

## Configuration

The plugin can be configured in two ways:

1. **Via XNAT Admin UI** (Recommended): Navigate to **Administer → Plugin Settings → DICOMweb Plugin Configuration**
2. **Via Properties File**: Add properties to `{xnat-home}/config/xnat.properties` or `xnat-conf.properties`

All configuration parameters have sensible defaults and are optional.

### Pagination Settings

Control the default and maximum page sizes for QIDO-RS query responses:

**Via Admin UI:**
- Go to Administer → Plugin Settings → DICOMweb Plugin Configuration
- Set "Default Page Size" and "Max Page Size"

**Via Properties File:**
```properties
# Default page size when limit parameter is not specified (default: 100)
dicomweb.defaultPageSize=100

# Maximum allowed page size - requests exceeding this are capped (default: 1000)
dicomweb.maxPageSize=1000
```

**Recommendations:**
- Smaller page sizes (50-100): Better for slow networks or limited client memory
- Larger page sizes (200-500): Better for high-performance deployments

### Multipart Upload Settings

Configure memory usage for STOW-RS (upload) operations:

**Via Properties File:**
```properties
# Memory threshold in bytes - files larger than this are written to disk (default: 10485760 = 10MB)
dicomweb.memoryThreshold=10485760
```

**Recommendations:**
- High memory systems (16GB+): `52428800` (50MB) for faster uploads
- Low memory systems (<8GB): `5242880` (5MB) to reduce memory pressure
- Default (10MB): Good balance for most deployments

### BulkData Settings

Control when DICOM attributes use BulkDataURI references instead of inline values:

**Via Properties File:**
```properties
# Threshold in bytes - attributes larger than this use BulkDataURI (default: 1024 = 1KB)
dicomweb.bulkDataThreshold=1024
```

**Recommendations:**
- Smaller threshold (512-1024): More BulkDataURIs, smaller JSON responses (better for slow networks)
- Larger threshold (4096-8192): Fewer BulkDataURIs, larger JSON responses (better for fast networks)

### Example Complete Configuration

**Via Properties File** (`xnat.properties` or `xnat-conf.properties`):

```properties
# High-performance configuration for fast network, high-memory deployment
dicomweb.defaultPageSize=200
dicomweb.maxPageSize=2000
dicomweb.memoryThreshold=52428800
dicomweb.bulkDataThreshold=4096
```

```properties
# Low-resource configuration for limited memory/network
dicomweb.defaultPageSize=50
dicomweb.maxPageSize=500
dicomweb.memoryThreshold=5242880
dicomweb.bulkDataThreshold=512
```

**Note:** Changes via the Admin UI take effect immediately. Changes to properties files require an XNAT restart.

## API Endpoints

All endpoints are prefixed with `/xapi/dicomweb/projects/{projectId}`

### QIDO-RS (Query) Endpoints

- **Search Studies**: `GET /xapi/dicomweb/projects/{projectId}/studies`
  - Returns all studies (imaging sessions) in the project

- **Search Series**: `GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series`
  - Returns all series (scans) in a study

- **Search Instances**: `GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances`
  - Returns all instances in a series

### WADO-RS (Retrieve) Endpoints

- **Retrieve Instance**: `GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}`
  - Returns a single DICOM instance

- **Retrieve Instance Metadata**: `GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata`
  - Returns metadata for a single instance in JSON format

- **Retrieve Series**: `GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}`
  - Returns all instances in a series as multipart/related

- **Retrieve Study Metadata**: `GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/metadata`
  - Returns comprehensive study-level metadata including series/instance counts

- **Retrieve Series Metadata**: `GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/metadata`
  - Returns metadata for all instances in a series

- **Retrieve Study**: `GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}`
  - Returns all instances in a study as multipart/related

- **Retrieve Rendered Instance**: `GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered`
  - Returns a rendered JPEG image of the instance

### STOW-RS (Store) Endpoints

- **Store Instances**: `POST /xapi/dicomweb/projects/{projectId}/studies?strategy={strategyName}`
  - Upload DICOM instances via multipart/related request
  - Requires Edit permission on the project
  - Validates project exists before processing
  - Returns STOW-RS response with success/failure status per instance
  - **Strategy Parameter** (optional):
    - `GradualDicomImporter` (default) - Uses XNAT's standard import pipeline with full validation
    - `DirectWrite` - Direct write to prearchive with automatic session registration via PrearcDatabase API
    - Both strategies support automatic session registration and archiving

## Using with OHIF Viewer

1. Configure OHIF to use the DICOMweb endpoint:

```javascript
window.config = {
  routerBasename: '/',
  servers: {
    dicomWeb: [
      {
        name: 'XNAT',
        wadoUriRoot: 'https://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT',
        qidoRoot: 'https://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT',
        wadoRoot: 'https://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT',
        qidoSupportsIncludeField: false,
        imageRendering: 'wadors',
        thumbnailRendering: 'wadors',
      },
    ],
  },
};
```

2. Access OHIF with your XNAT credentials

## Using with VolView

1. In VolView, go to File > Remote
2. Enter the DICOMweb URL: `https://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT`
3. Authenticate with your XNAT credentials
4. Browse and load studies

## Authentication

The plugin uses XNAT's built-in authentication. Users must be authenticated with XNAT and have appropriate permissions to access the project.

## Requirements

- XNAT 1.9.0 or higher (implemented for 1.9.x)
- Java 8 or higher
- DICOM data stored in XNAT with proper StudyInstanceUID, SeriesInstanceUID, and SOPInstanceUID
- XNAT archive path (defaults to `/data/xnat/archive`, configurable via `xnat.archive` system property)

## Architecture

The plugin consists of:

- **DicomWebProxyPlugin**: Main plugin class
- **DicomWebConfig**: Spring configuration and CORS setup
- **QidoRsApi**: REST controller for QIDO-RS queries
- **WadoRsApi**: REST controller for WADO-RS retrieval
- **StowRsApi**: REST controller for STOW-RS storage
- **XnatDicomService/XnatDicomServiceImpl**: Service layer for accessing XNAT data
- **DicomWebUtils**: Utility methods for DICOM/JSON conversion
- **DicomWebTestPageApi**: Test page endpoint for validation

## Development

Build the plugin:
```bash
./gradlew build
```

Run tests:
```bash
./gradlew test
```

## License

This plugin follows the same license as XNAT.

## Support

For issues and feature requests, please use the project's issue tracker.
