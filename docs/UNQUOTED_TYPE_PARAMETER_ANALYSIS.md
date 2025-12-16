# Supporting Unquoted type Parameter Analysis

**Date:** December 2025
**Question:** Can we support `type=application/dicom` (without quotes) even though RFC 2045 requires quotes?

---

## Current Status

### Patch Applied

The `xnat-web-multipart-fix.patch` has been applied to XNAT's `WebConfig.java`:

```java
@Bean
public MultipartResolver multipartResolver() {
    // Wrap resolver to exclude multipart/related
    return new MultipartResolver() {
        @Override
        public boolean isMultipart(HttpServletRequest request) {
            String contentType = request.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("multipart/related")) {
                // Don't process multipart/related - let endpoint handle it
                return false;
            }
            return delegate.isMultipart(request);
        }
        // ...
    };
}
```

**Purpose**: Bypass Spring's `StandardServletMultipartResolver` for `multipart/related` requests.

### Test Results (With Patch Applied)

| Content-Type Format | HTTP Code | Result |
|---------------------|-----------|--------|
| `type="application/dicom"; boundary=xxx` | 200 | ✅ Success |
| `type=application/dicom; boundary=xxx` | 415 | ❌ Still fails |

**Conclusion**: The patch does **NOT** solve the HTTP 415 error for unquoted type parameters.

---

## Root Cause Analysis

### Where Does HTTP 415 Error Occur?

The HTTP 415 error happens **before** our code is reached. The error occurs in:

1. **Spring MVC Request Mapping Layer**
   - **Component**: `RequestMappingHandlerMapping`
   - **Method**: `AbstractHandlerMethodMapping.getHandlerInternal()`
   - **Process**: Matches incoming request to `@XapiRequestMapping` handlers

2. **Content-Type Parsing**
   - **Component**: `org.springframework.http.MediaType`
   - **Method**: `MediaType.parseMediaType(String)`
   - **Behavior**: Strictly validates parameter syntax per RFC 2045

### Spring MediaType.parseMediaType() Behavior

```java
// Spring Framework source (pseudo-code)
public static MediaType parseMediaType(String mediaType) {
    // Parse: type/subtype; param1=value1; param2=value2
    // Enforces: parameter values with tspecials MUST be quoted

    if (paramValue.contains("/") && !isQuoted(paramValue)) {
        throw InvalidMediaTypeException();
    }
}
```

**Key Point**: Spring's `MediaType` parser **enforces RFC 2045 rules** and rejects `type=application/dicom` because `/` is unquoted.

### Request Processing Flow

```
HTTP Request
    ↓
[Tomcat receives request]
    ↓
[Spring DispatcherServlet]
    ↓
[RequestMappingHandlerMapping] ← HTTP 415 occurs HERE
    ↓                              (Content-Type parsing fails)
    ↓
[MultipartResolver] ← Patch bypasses this (but too late!)
    ↓
[StowRsApi.storeInstances()] ← Never reached if 415
    ↓
[Mime4jHybridParser] ← Would accept unquoted (but never reached)
```

**Problem**: The error occurs at **step 3** (request mapping), before reaching **step 4** (multipart resolver).

---

## Why Patch Doesn't Help

The `xnat-web-multipart-fix.patch` only affects `MultipartResolver`:

```
Content-Type: multipart/related; type=application/dicom; boundary=xxx
                                      ↑
                                      This causes parsing failure
                                      in RequestMappingHandlerMapping
                                      BEFORE MultipartResolver is called
```

**Timeline**:
1. ❌ Request arrives → Spring parses Content-Type → **FAILS** (HTTP 415)
2. ⏭️ MultipartResolver check → **Skipped** (request already rejected)
3. ⏭️ Our API code → **Never reached**

---

## Potential Solutions

### Option 1: Custom Content-Type Normalization Filter ⭐ (Feasible)

Add a `Filter` that **rewrites** the Content-Type header before Spring processes it.

**Implementation**:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ContentTypeNormalizationFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String contentType = httpRequest.getContentType();

        // Fix: type=application/dicom → type="application/dicom"
        if (contentType != null && contentType.contains("multipart/related")) {
            String normalized = normalizeContentType(contentType);
            if (!normalized.equals(contentType)) {
                // Wrap request to return normalized Content-Type
                httpRequest = new ContentTypeOverrideRequestWrapper(httpRequest, normalized);
            }
        }

        chain.doFilter(httpRequest, response);
    }

    private String normalizeContentType(String contentType) {
        // Parse and add quotes to unquoted parameters
        // type=application/dicom → type="application/dicom"
        return contentType.replaceAll(
            "type=application/dicom(?!\")",
            "type=\"application/dicom\""
        );
    }
}

class ContentTypeOverrideRequestWrapper extends HttpServletRequestWrapper {
    private final String overrideContentType;

    public ContentTypeOverrideRequestWrapper(HttpServletRequest request, String contentType) {
        super(request);
        this.overrideContentType = contentType;
    }

    @Override
    public String getContentType() {
        return overrideContentType;
    }

    @Override
    public String getHeader(String name) {
        if ("Content-Type".equalsIgnoreCase(name)) {
            return overrideContentType;
        }
        return super.getHeader(name);
    }
}
```

**Pros**:
- ✅ Fixes the issue before Spring sees it
- ✅ Non-invasive (doesn't modify core Spring behavior)
- ✅ Can be toggled on/off easily

**Cons**:
- ⚠️ Regex parsing may be fragile
- ⚠️ Need to handle all edge cases (quoted boundary, etc.)

---

### Option 2: Custom RequestMappingHandlerMapping (Complex)

Override Spring's request mapping to use lenient Content-Type parsing.

**Complexity**: Very high
**Risk**: Could break other XNAT functionality
**Recommendation**: ❌ Not recommended

---

### Option 3: Accept Current Behavior (RFC Compliant)

Keep existing behavior and **require clients to follow RFC 2045**.

**Rationale**:
1. ✅ RFC 2045 **explicitly requires** quotes for values with `/`
2. ✅ All major DICOMweb implementations use quotes (DCM4CHE, dicomweb-client, etc.)
3. ✅ DICOM PS3.18 defers to RFC 2045
4. ✅ No compatibility issues with compliant clients

**Recommendation**: ⭐ **Preferred approach**

---

## Recommendation

### Recommended: Keep RFC-Compliant Behavior

**Reasons**:

1. **Standards Compliance**
   - RFC 2045 is clear: tspecials must be quoted
   - DICOM PS3.18 defers to RFC 2045
   - Being strict encourages proper client implementations

2. **Industry Alignment**
   - DCM4CHE: Always uses quotes
   - dicomweb-client: Fixed to use quotes (Issue #30, Oct 2020)
   - DICOMcloud: Requires quotes
   - No known compliant client sends unquoted type

3. **Security & Robustness**
   - Strict parsing prevents ambiguous interpretations
   - Reduces attack surface for header injection
   - Consistent behavior across deployments

4. **Minimal Impact**
   - Clients that follow standards already work
   - Non-compliant clients should be fixed at source
   - Clear error message (HTTP 415) guides debugging

### If Lenient Behavior is Required

If you **must** support non-compliant clients, implement **Option 1** (Content-Type Normalization Filter).

**Conditions**:
- Document that this is a **compatibility workaround**
- Make it **configurable** (can be disabled)
- Add **logging** when normalization occurs
- Consider **deprecating** in future versions

**Implementation Priority**: Low (only if multiple clients have issues)

---

## Testing Non-Compliant Clients

If you encounter clients that send unquoted type parameters:

1. **Identify the client** (user agent, library version)
2. **Check if update available** (may already be fixed)
3. **Report to client maintainers** (it's their bug, not ours)
4. **Provide workaround instructions**:
   ```bash
   # Wrong (will fail)
   Content-Type: multipart/related; type=application/dicom; boundary=xxx

   # Correct (will work)
   Content-Type: multipart/related; type="application/dicom"; boundary=xxx
   ```

---

## Known Non-Compliant Clients

Based on GitHub issues:

| Client | Issue | Status | Fix Available |
|--------|-------|--------|---------------|
| **dicomweb-client** (JS) | Issue #30 | ✅ Fixed Oct 2020 | Yes (v0.5.3+) |
| **DICOMcloud** client | Issue #12 | ✅ Fixed | Yes |
| **curl** (manual) | User error | N/A | Documentation |

**No known actively-maintained client** currently sends unquoted type parameters.

---

## Documentation Update

Update user documentation to clarify:

### For API Users

```markdown
## STOW-RS Content-Type Header

**Required format**:
Content-Type: multipart/related; type="application/dicom"; boundary=<boundary>
                                       ^               ^
                                       Quotes are REQUIRED

**Common mistakes**:
❌ type=application/dicom  (missing quotes - HTTP 415 error)
✅ type="application/dicom" (correct)

**Reason**: The '/' character in 'application/dicom' is a special character
(tspecial) per RFC 2045 and MUST be enclosed in quotes.

**Example**:
```bash
curl -X POST \
  -H 'Content-Type: multipart/related; type="application/dicom"; boundary=myboundary' \
  http://localhost:8080/xapi/dicomweb/projects/PROJECT/studies
```
```

---

## Conclusion

### Current Status
- ✅ Patch applied (bypasses MultipartResolver for multipart/related)
- ❌ Still requires quoted type parameter (HTTP 415 if unquoted)
- ✅ This is **correct behavior** per RFC 2045

### Can We Support Unquoted Type?
- **Technically**: Yes (via Filter workaround)
- **Should We**: **No** - violates RFC 2045, no real-world need
- **Alternative**: Document requirement clearly, guide users to fix clients

### Action Items
1. ✅ Document requirement in USER_GUIDE.md
2. ✅ Add to ISSUE_20_ANALYSIS.md (help users debug)
3. ✅ Create CONTENT_TYPE_QUOTING_ANALYSIS.md (this document)
4. ⏸️ Implement Filter (only if multiple user complaints)

---

## References

- **RFC 2045**: https://www.rfc-editor.org/rfc/rfc2045.html (Section 5.1)
- **DICOM PS3.18**: https://dicom.nema.org/medical/dicom/current/output/html/part18.html
- **Spring MediaType**: `org.springframework.http.MediaType.parseMediaType()`
- **Issue #30**: https://github.com/dcmjs-org/dicomweb-client/issues/30
- **Issue #12**: https://github.com/DICOMcloud/DICOMcloud/issues/12
