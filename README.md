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

- **Store Instances**: `POST /xapi/dicomweb/projects/{projectId}/studies`
  - Upload DICOM instances via multipart/related request
  - Requires Edit permission on the project
  - Returns STOW-RS response with success/failure status per instance

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
