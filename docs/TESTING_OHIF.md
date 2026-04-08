# Testing WADO-RS Frame Retrieval with OHIF

This guide shows how to test the WADO-RS frame-level retrieval feature with OHIF viewer.

## Quick Start (Docker - Easiest)

### 1. Create OHIF Config

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
        wadoUriRoot: 'http://localhost:1026/xnat/xapi/dicomweb/projects/YOUR_PROJECT',
        qidoRoot: 'http://localhost:1026/xnat/xapi/dicomweb/projects/YOUR_PROJECT',
        wadoRoot: 'http://localhost:1026/xnat/xapi/dicomweb/projects/YOUR_PROJECT',
        imageRendering: 'wadors',
        thumbnailRendering: 'wadors',
      },
    },
  ],
};
EOF
```

**Important**: Replace `YOUR_PROJECT` with your actual XNAT project ID.

### 2. Run OHIF

```bash
docker run -d \
  --name ohif-viewer \
  -p 3000:80 \
  -v $(pwd)/config/default.js:/usr/share/nginx/html/app-config.js \
  ohif/app:latest
```

### 3. Test

1. Open: http://localhost:3000
2. OHIF loads studies from XNAT
3. Open a study
4. Check DevTools → Network tab for frame requests

You should see:
```
GET .../frames/1    200 OK  application/octet-stream
GET .../frames/2    200 OK  application/octet-stream
GET .../frames/3    200 OK  application/octet-stream
```

---

## Testing with curl

### Single Frame

```bash
curl -v \
  "http://localhost:1026/xnat/xapi/dicomweb/projects/test/studies/1.3.6.../series/1.3.6.../instances/1.3.6.../frames/1" \
  --user admin:admin \
  -o frame1.raw

# Check response
file frame1.raw  # Should be: data
ls -lh frame1.raw  # Should be reasonable size (e.g., 32KB for 128x128x16bit)
```

### Multiple Frames

```bash
curl -v \
  "http://localhost:1026/xnat/xapi/dicomweb/projects/test/studies/1.3.6.../series/1.3.6.../instances/1.3.6.../frames/1,2,3" \
  --user admin:admin

# Response headers should show:
# Content-Type: multipart/related; type="application/octet-stream"; boundary=...
```

---

## Local OHIF Build

### 1. Clone and Setup

```bash
git clone https://github.com/OHIF/Viewers.git
cd Viewers
git checkout v3.8.0  # Use stable version
yarn install
```

### 2. Configure

Edit `platform/app/public/config/default.js`:

```javascript
window.config = {
  dataSources: [
    {
      namespace: '@ohif/extension-default.dataSourcesModule.dicomweb',
      sourceName: 'dicomweb',
      configuration: {
        friendlyName: 'XNAT DICOMweb',
        wadoUriRoot: 'http://localhost:1026/xnat/xapi/dicomweb/projects/YOUR_PROJECT',
        qidoRoot: 'http://localhost:1026/xnat/xapi/dicomweb/projects/YOUR_PROJECT',
        wadoRoot: 'http://localhost:1026/xnat/xapi/dicomweb/projects/YOUR_PROJECT',
        imageRendering: 'wadors',
      },
    },
  ],
};
```

### 3. Run

```bash
yarn run dev
# Opens at http://localhost:3000
```

---

## Debugging

### Enable XNAT Logging

Edit `$XNAT_HOME/config/prefs/log4j.properties`:

```properties
log4j.logger.org.nrg.xnat.dicomweb=DEBUG
```

Restart XNAT and watch logs:

```bash
tail -f $XNAT_HOME/logs/catalina.out | grep -i frame
```

Expected output:
```
INFO  o.n.x.d.s.XnatDicomServiceImpl - Retrieving frames 1 from instance 1.3.6... (total frames: 89)
DEBUG o.n.x.d.s.XnatDicomServiceImpl - Extracted uncompressed frame 0 (32768 bytes)
INFO  o.n.x.d.s.XnatDicomServiceImpl - Retrieved 1 frame(s) from instance: 1.3.6...
```

### Check Browser DevTools

**Network Tab**:
- Filter by "frames"
- Should see individual frame requests (not full instance downloads)
- Check response headers: `Content-Type: application/octet-stream`
- Response size should match frame size (e.g., 32KB per frame)

**Console Tab**:
- No errors about "Failed to load frame"
- No errors about pixel data format

---

## Troubleshooting

### CORS Errors

**Symptom**: Console shows CORS policy errors

**Fix**: Add CORS headers to XNAT or use nginx proxy:

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

### Authentication Issues

**Symptom**: 401 Unauthorized errors

**Options**:
1. **Basic Auth** in OHIF config:
   ```javascript
   configuration: {
     // ...
     headers: {
       Authorization: 'Basic ' + btoa('username:password')
     }
   }
   ```

2. **Session Cookie**: Login to XNAT first, then open OHIF

3. **API Token**: Create XNAT alias token and use in Authorization header

### 404 on Frame Requests

**Symptom**: Frame requests return 404 Not Found

**Checks**:
1. Verify plugin is deployed: `ls $XNAT_HOME/plugins/ | grep dicomweb`
2. Check plugin loaded: XNAT logs should show "Loading plugin: xnat-dicomweb-plugin"
3. Verify endpoint exists: `curl -u admin:admin http://localhost:1026/xnat/xapi/dicomweb/projects/test/studies/.../metadata`
4. Check frame endpoint specifically

### Corrupted Images

**Symptom**: Images display garbled or incorrectly

**Possible causes**:
1. Using old plugin version (< 1.1.3)
2. Compressed data incorrectly extracted
3. Wrong pixel data format

**Check logs** for:
```
ERROR o.n.x.d.s.XnatDicomServiceImpl - Error extracting frame pixel data
```

---

## Success Indicators

✅ **Frame Retrieval Working**:
- OHIF loads and displays studies
- Multi-frame images render correctly
- Network tab shows individual frame requests (not full instances)
- Frame requests return 200 OK
- Images display without corruption
- Scroll through frames smoothly

✅ **Logs Show**:
```
INFO - Retrieving frames 1 from instance...
DEBUG - Extracted uncompressed frame 0 (32768 bytes)
INFO - Retrieved 1 frame(s) from instance
```

✅ **Network Tab Shows**:
```
GET .../frames/1    200  32KB  application/octet-stream  50ms
GET .../frames/2    200  32KB  application/octet-stream  45ms
GET .../frames/3    200  32KB  application/octet-stream  48ms
```

---

## Advanced: Test with Real Multi-frame Data

Upload a multi-frame DICOM to XNAT:

```bash
# Example: PET scan with 89 frames
# Each frame request should return ~32KB (for 128x128x16bit)
# Total data transfer = 89 × 32KB = 2.8MB
# Compare to downloading full instance: could be 5-10MB+

# OHIF should request frames on-demand as you scroll
```

---

## Questions?

- Check GitHub issues: https://github.com/mrjamesdickson/xnat_dicomweb_plugin/issues
- Review DICOM PS3.18 spec: Section 10.4 (WADO-RS Retrieve Transaction)
- OHIF documentation: https://docs.ohif.org/
