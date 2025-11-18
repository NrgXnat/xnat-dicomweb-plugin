# XNAT Core Patch for DICOMweb STOW-RS Support

**Date:** November 18, 2025
**Status:** ✅ Tested and Working
**XNAT Version:** 1.9.x (tested on 1.9.2.1)

---

## Overview

This patch modifies XNAT's `WebConfig.java` to enable DICOMweb STOW-RS functionality by allowing `multipart/related` requests to bypass Spring's `StandardServletMultipartResolver`.

**Without this patch:** STOW-RS endpoints receive 0 bytes (request body consumed by multipart resolver)
**With this patch:** STOW-RS works correctly, enabling full DICOMweb support (QIDO-RS, WADO-RS, STOW-RS)

---

## The Problem

Spring's `StandardServletMultipartResolver` (configured in `xnat-web/src/main/java/org/nrg/xnat/configuration/WebConfig.java:160`) intercepts ALL `multipart/*` requests and attempts to parse them as `multipart/form-data`.

DICOMweb STOW-RS uses `multipart/related` (not `multipart/form-data`), which causes the resolver to:
1. Consume the entire request InputStream
2. Fail with "Content type not supported"
3. Leave the InputStream empty for plugin code

**Result:** Plugin controllers receive 0 bytes, making STOW-RS impossible to implement.

---

## The Solution

Wrap the `StandardServletMultipartResolver` with a custom `MultipartResolver` that:
1. Checks the `Content-Type` header
2. Returns `false` for `multipart/related` requests (skips processing)
3. Delegates to standard resolver for `multipart/form-data` (preserves existing functionality)

This allows plugin code to read `multipart/related` requests directly while maintaining backward compatibility with existing XNAT multipart handling.

---

## Installation

### Option 1: Apply the Patch (Recommended)

```bash
# Navigate to your xnat-web directory
cd /path/to/xnat-web

# Apply the patch
git apply /path/to/xnat-web-multipart-fix.patch

# Verify the changes
git diff src/main/java/org/nrg/xnat/configuration/WebConfig.java

# Build XNAT
./gradlew war

# Deploy the WAR to your XNAT instance
```

### Option 2: Manual Edit

Edit `src/main/java/org/nrg/xnat/configuration/WebConfig.java`:

1. Add imports (around line 40):
```java
import org.springframework.web.multipart.MultipartHttpServletRequest;
import javax.servlet.http.HttpServletRequest;
```

2. Replace the `multipartResolver()` method (around line 160):
```java
@Bean
public MultipartResolver multipartResolver() {
    final StandardServletMultipartResolver delegate = new StandardServletMultipartResolver();

    // Wrap the resolver to exclude multipart/related (used by DICOMweb STOW-RS)
    // This allows plugin code to handle multipart/related requests directly
    return new MultipartResolver() {
        @Override
        public boolean isMultipart(HttpServletRequest request) {
            String contentType = request.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("multipart/related")) {
                // Don't process multipart/related - let the endpoint handle it
                return false;
            }
            return delegate.isMultipart(request);
        }

        @Override
        public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request) {
            return delegate.resolveMultipart(request);
        }

        @Override
        public void cleanupMultipart(MultipartHttpServletRequest request) {
            delegate.cleanupMultipart(request);
        }
    };
}
```

---

## Testing

### 1. Verify Existing Functionality (Multipart Form Data)

```bash
# Test file upload (should still work)
curl -u your_username:your_password -F "file=@test.zip" \
  http://your-xnat-server/xapi/upload
```

### 2. Test STOW-RS (Multipart Related)

```bash
# Upload DICOM file via STOW-RS
BOUNDARY="DicomBoundary123"
DICOM_FILE="test.dcm"

{
    echo -en "--$BOUNDARY\r\n"
    echo -en "Content-Type: application/dicom\r\n\r\n"
    cat "$DICOM_FILE"
    echo -en "\r\n--$BOUNDARY--\r\n"
} | curl -u your_username:your_password \
    -H "Content-Type: multipart/related; boundary=$BOUNDARY" \
    -H "Accept: application/dicom+json" \
    --data-binary @- \
    http://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT/studies

# Expected response:
# {
#   "00081199": {"vr":"SQ","Value":[...]},  # Success
#   "00081198": {"vr":"SQ"}                 # No failures
# }
```

### 3. Verify DICOM Import

```bash
# Query via QIDO-RS (should show uploaded study)
curl -u your_username:your_password \
  http://your-xnat-server/xapi/dicomweb/projects/YOUR_PROJECT/studies
```

---

## Verification

After applying the patch and rebuilding:

1. **Build succeeds**: `./gradlew war` completes without errors
2. **XNAT starts**: Server startup logs show no errors
3. **Existing multipart uploads work**: File uploads still function
4. **STOW-RS works**: Can upload DICOM files and query them via QIDO-RS

### Expected Debug Output

With the debug logging in the patch, you should see in the logs:

```
=== MultipartResolver.isMultipart() called: contentType=multipart/related; boundary=...
=== MultipartResolver: Skipping multipart/related request
```

**Note:** The debug `System.out.println()` statements can be removed in production if desired.

---

## Impact Assessment

### What Changes
- `multipart/related` requests bypass the multipart resolver
- Plugin code can read `multipart/related` request bodies directly

### What Stays the Same
- `multipart/form-data` requests processed normally
- All existing file upload functionality preserved
- No changes to XNAT REST APIs
- No changes to plugin loading or registration

### Compatibility
- ✅ Backward compatible with existing plugins
- ✅ No database changes required
- ✅ No configuration changes required
- ✅ Works with XNAT 1.9.x

---

## Production Deployment

### For Docker Deployments

If using docker-compose, mount the webapps directory to easily deploy custom WARs:

```yaml
# docker-compose.yml
services:
  xnat-web:
    volumes:
      - ./xnat/webapps:/usr/local/tomcat/webapps
      - ./xnat/plugins:${XNAT_HOME}/plugins
      # ... other volumes
```

Then extract the patched WAR:
```bash
cd xnat/webapps/ROOT
rm -rf *
unzip /path/to/xnat-web-<version>.war
```

### For Traditional Deployments

Replace the existing XNAT WAR in Tomcat's webapps directory:

```bash
# Backup current WAR
cp $TOMCAT_HOME/webapps/ROOT.war $TOMCAT_HOME/webapps/ROOT.war.backup

# Deploy patched WAR
cp xnat-web-<version>.war $TOMCAT_HOME/webapps/ROOT.war

# Restart Tomcat
systemctl restart tomcat
```

---

## Rollback

If issues occur, simply revert the changes:

```bash
# Revert the patch
cd /path/to/xnat-web
git checkout src/main/java/org/nrg/xnat/configuration/WebConfig.java

# Rebuild
./gradlew war

# Redeploy
```

Or restore from backup:
```bash
cp $TOMCAT_HOME/webapps/ROOT.war.backup $TOMCAT_HOME/webapps/ROOT.war
systemctl restart tomcat
```

---

## Why This Approach?

### Alternatives Considered

1. **Custom HttpMessageConverter** - Doesn't run before multipart resolver
2. **@RequestBody byte[]** - Spring won't use converter for multipart
3. **Servlet Filter** - Plugin filters don't register in Spring filter chain
4. **Direct InputStream reading** - Already consumed by resolver

### Why This Works

The multipart resolver runs in the `DispatcherServlet` processing pipeline BEFORE:
- Filters (except servlet container filters)
- Message converters
- Controller methods

By wrapping the resolver bean itself, we intercept at the earliest possible point in Spring's request processing.

---

## Future Considerations

### Upstream XNAT PR

This patch should be proposed as a pull request to the official XNAT repository at:
https://github.com/NrgXnat/xnat

### Alternative: Content-Type Check

Instead of checking for `multipart/related`, could check for specific paths:

```java
String uri = request.getRequestURI();
if (uri != null && uri.contains("/dicomweb/") &&
    contentType != null && contentType.contains("multipart/related")) {
    return false;
}
```

This is more conservative but requires updating for each plugin that needs `multipart/related` support.

---

## Related Issues

- DICOMweb Plugin: https://github.com/mrjamesdickson/xnat_dicomweb_proxy/pull/19
- DICOM PS3.18 Section 10.5 - STOW-RS specification
- Spring Framework multipart resolver documentation

---

## Support

For questions or issues:
1. Check the DICOMweb plugin documentation: `STOW_RS_BLOCKER.md`
2. Review the full implementation in this PR
3. Test with the included test scripts

---

## License

This patch modifies XNAT core, which is licensed under the Simplified BSD License.
The modification is provided as-is for testing and evaluation purposes.

---

**End of Documentation**
