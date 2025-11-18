# Test Data

This directory is for DICOM test files used by the test scripts.

## Getting Test DICOM Files

The test scripts expect DICOM files to be available in this directory. You can obtain test DICOM files from:

### 1. Public DICOM Test Datasets

- **TCIA (The Cancer Imaging Archive)**: https://www.cancerimagingarchive.net/
  - Free public medical imaging datasets
  - Requires registration (free)
  - Example: Download a small study and place DICOM files here

- **Osirix DICOM Sample Images**: https://www.osirix-viewer.com/resources/dicom-image-library/
  - Small sample datasets for testing
  - No registration required

- **dcm4che Test Data**: https://github.com/dcm4che/dcm4che/tree/master/dcm4che-test-data
  - Small test DICOM files
  - Part of the dcm4che project

### 2. Directory Structure

The test scripts look for DICOM files in:
```
test/data/
  └── 2/DICOM/*.dcm
```

Create this structure and place your DICOM files there:
```bash
mkdir -p test/data/2/DICOM
# Copy your DICOM files to test/data/2/DICOM/
```

### 3. Using the Test Scripts

Once you have DICOM files in place:

**Main test script** (requires all parameters):
```bash
./test_stow_rs.sh http://your-xnat-server YOUR_PROJECT your_username your_password
```

**Test suite** (with defaults):
```bash
./test/test_stow_rs.sh http://your-xnat-server YOUR_PROJECT your_username your_password
```

## Privacy Note

**Do NOT commit real patient DICOM data to the repository.**

- Use only de-identified/anonymized test datasets
- Public datasets from TCIA are already de-identified
- Test data is excluded from git via `.gitignore`

## File Size Considerations

DICOM files can be large. A typical study might be:
- Single instance: ~500KB - 2MB
- Small series (10 instances): ~5-20MB
- Full study (multiple series): ~50-500MB

For testing STOW-RS, even a single DICOM instance is sufficient.
