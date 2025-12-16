# Content-Type Parameter Quoting in STOW-RS

**Date:** December 2025
**Topic:** Analysis of `type` parameter quoting requirements in multipart/related Content-Type headers

---

## Question

Is the double quote required for the `type` parameter in STOW-RS Content-Type headers?

```
Content-Type: multipart/related; type="application/dicom"; boundary=myboundary
                                        ^               ^
                                        Are these quotes required?
```

---

## Short Answer

**YES, quotes are REQUIRED** for `type="application/dicom"` because the value contains the `/` character, which is a **tspecial** character according to RFC 2045.

---

## RFC 2045 Specification

### Parameter Value Syntax

According to **RFC 2045 Section 5.1**:

```
parameter := attribute "=" value
value     := token / quoted-string
token     := 1*<any (US-ASCII) CHAR except SPACE, CTLs, or tspecials>
tspecials := "(" / ")" / "<" / ">" / "@" / "," / ";" / ":" /
             "\" / "\"" / "/" / "[" / "]" / "?" / "="
```

### Key Points

1. **Parameter values** can be either:
   - **Token**: Unquoted string (letters, digits, certain symbols)
   - **Quoted-string**: String enclosed in double quotes

2. **tspecials characters** that **require quoting**:
   ```
   ( ) < > @ , ; : \ " / [ ] ? =
   ```

3. **The `/` character is a tspecial** and **must** be in a quoted-string when used in parameter values.

### Application to `application/dicom`

- Value: `application/dicom`
- Contains: `/` (forward slash)
- `/` is a **tspecial** character
- **Conclusion**: Must be quoted as `"application/dicom"`

### Example from RFC 2045

The RFC states that for simple tokens (without tspecials):

```
charset=us-ascii       (unquoted - OK because no tspecials)
charset="us-ascii"     (quoted - also OK, equivalent)
```

**These are equivalent** for simple tokens.

But for values with tspecials:

```
type=application/dicom     ❌ RFC VIOLATION (contains '/')
type="application/dicom"   ✅ RFC COMPLIANT (properly quoted)
```

---

## DICOM Standard

### DICOM PS3.18 (DICOMweb Standard)

DICOM PS3.18 Section 6.6.1 specifies STOW-RS request headers but defers to:
- **RFC 2387**: Multipart/Related Content-Type
- **RFC 2045**: MIME Part One (parameter syntax)

**Effective requirement**: Follow RFC 2045 rules → **quotes required**

---

## Real-World Evidence

### Test Results (This Implementation)

| Content-Type Format | HTTP Code | Result |
|---------------------|-----------|--------|
| `type="application/dicom"; boundary=xxx` | 200 | ✅ Success |
| `type=application/dicom; boundary=xxx` | 415 | ❌ Unsupported Media Type |
| `boundary=xxx` (no type) | 200 | ✅ Success |
| `type="application/dicom"; boundary="xxx"` | 200 | ✅ Success |

**Conclusion**: Spring MVC (used in XNAT) strictly enforces RFC 2045 rules.

### Known Issues in Other DICOMweb Implementations

#### 1. dcmjs-org/dicomweb-client Issue #30

**Problem**: JavaScript DICOMweb client was sending unquoted type parameter
**Date**: October 2020
**Link**: https://github.com/dcmjs-org/dicomweb-client/issues/30

**Original (incorrect) format**:
```
Content-Type: multipart/related; type=application/dicom; boundary=2afeebaf-038d-792a-f952-d8e249ba8e96
```

**Fixed format**:
```
Content-Type: multipart/related; type="application/dicom"; boundary="2afeebaf-038d-792a-f952-d8e249ba8e96"
```

**Impact**: .NET servers rejected requests with unquoted parameters
**Resolution**: PR #31 added proper quoting

#### 2. DICOMcloud/DICOMcloud Issue #12

**Problem**: .NET STOW-RS server's `IsMimeMultipartContent("related")` validation rejected unquoted type
**Link**: https://github.com/DICOMcloud/DICOMcloud/issues/12

**Root cause**: .NET framework's MIME parser strictly enforces RFC compliance
**Solution**: Clients must send properly quoted Content-Type headers

### DCM4CHE Examples

DCM4CHE (reference DICOMweb implementation) **always uses quotes**:

```bash
curl -X POST \
  -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=myboundary" \
  http://localhost:8080/dcm4chee-arc/aets/DCM4CHEE/rs/studies \
  --data-binary @dicom.mime
```

**Source**: https://github.com/dcm4che/dcm4chee-arc-light/wiki/Store-objects-by-STOW-RS

---

## Parser Behavior

### Apache Mime4J (Used in this plugin)

- **Configuration**: Lenient parsing (`setStrictParsing(false)`)
- **Behavior**: Can parse both quoted and unquoted formats
- **Documentation**: "Designed to be extremely tolerant against messages violating the standards"

**However**: The HTTP 415 error occurs **before** reaching the parser, at the **Spring MVC layer**.

### Spring MVC

- **Behavior**: Strictly validates Content-Type headers against RFC 2045
- **Rejects**: `type=application/dicom` (unquoted) with HTTP 415
- **Accepts**: `type="application/dicom"` (quoted)

### Python email.message (Standard library)

```python
from email.message import Message

# Both formats parse successfully (lenient)
msg = Message()
msg['Content-Type'] = 'multipart/related; type="application/dicom"; boundary=x'
# → Parses OK

msg['Content-Type'] = 'multipart/related; type=application/dicom; boundary=x'
# → Also parses OK (lenient behavior)
```

**Conclusion**: Standard RFC parsers can be lenient, but **strict implementations reject unquoted values**.

---

## Industry Practice

### Survey of DICOMweb Clients

| Client/Server | Uses Quotes | Notes |
|---------------|-------------|-------|
| **DCM4CHE** | ✅ Yes | Reference implementation |
| **dicomweb-client** (JS) | ✅ Yes | Fixed in Oct 2020 |
| **DICOMcloud** (.NET) | ✅ Required | Strict validation |
| **XNAT DICOMweb Proxy** | ✅ Required | Spring MVC enforces |
| **OHIF Viewer** | ✅ Yes | Uses dicomweb-client |

**Conclusion**: **All major implementations use quoted parameters**.

---

## Boundary Parameter

### Do Boundary Values Need Quotes?

According to RFC 2046, boundary values have **different rules**:

```
boundary := 0*69<bchars> bcharsnospace
bchars := bcharsnospace / " "
bcharsnospace := DIGIT / ALPHA / "'" / "(" / ")" / "+" / "_" / "," / "-" / "." / "/" / ":" / "=" / "?"
```

**Key difference**: Boundary can include `/` unquoted because it's explicitly listed in `bcharsnospace`.

### Examples

```
boundary=simple123                     ✅ OK (simple token)
boundary="simple123"                   ✅ OK (quoted, also valid)
boundary=----WebKitFormBoundary123     ✅ OK (complex but valid boundary chars)
boundary="----WebKitFormBoundary123"   ✅ OK (quoted, also valid)
```

**Both work**, but quoting is **recommended** for consistency.

---

## Recommendation

### For STOW-RS Clients

**ALWAYS use quotes** for both `type` and `boundary` parameters:

```
Content-Type: multipart/related; type="application/dicom"; boundary="myboundary123"
```

**Rationale**:
1. ✅ RFC 2045 compliant
2. ✅ Works with all known DICOMweb servers
3. ✅ Prevents compatibility issues
4. ✅ Future-proof

### For STOW-RS Servers

**Accept both formats** if possible (lenient parsing), but **recommend quoted format** in documentation.

**This implementation** (XNAT DICOMweb Proxy):
- Spring MVC requires quotes (enforces RFC strictly)
- Apache Mime4J parser accepts both (lenient)
- HTTP 415 error returned for unquoted type parameter

---

## Common Mistakes

### ❌ Wrong

```bash
# Missing quotes on type
curl -H "Content-Type: multipart/related; type=application/dicom; boundary=xxx"

# Single quotes (not valid in HTTP headers)
curl -H "Content-Type: multipart/related; type='application/dicom'; boundary=xxx"

# Missing type parameter entirely
curl -H "Content-Type: multipart/related; boundary=xxx"
# ↑ Works, but not recommended (ambiguous content type)
```

### ✅ Correct

```bash
# Both parameters quoted
curl -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=\"myboundary\""

# Type quoted, boundary unquoted (also OK if boundary chars are valid)
curl -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=myboundary"
```

---

## References

### Standards

- **RFC 2045**: Multipurpose Internet Mail Extensions (MIME) Part One
  - Section 5.1: Syntax of the Content-Type Header Field
  - https://www.rfc-editor.org/rfc/rfc2045.html

- **RFC 2387**: The MIME Multipart/Related Content-type
  - https://www.rfc-editor.org/rfc/rfc2387.html

- **DICOM PS3.18**: Web Services
  - Section 6.6: STOW-RS (Store Over the Web by RESTful Services)
  - https://dicom.nema.org/medical/dicom/current/output/html/part18.html

### Real-World Issues

- **dcmjs-org/dicomweb-client Issue #30**:
  "STOW-RS is not encoding the \"Content-Type\" according to standard"
  https://github.com/dcmjs-org/dicomweb-client/issues/30

- **DICOMcloud/DICOMcloud Issue #12**:
  "STOW-RS Content-Type multipart not recognised"
  https://github.com/DICOMcloud/DICOMcloud/issues/12

- **DCM4CHE Wiki**:
  "Store objects by STOW-RS"
  https://github.com/dcm4che/dcm4chee-arc-light/wiki/Store-objects-by-STOW-RS

---

## Summary

| Aspect | Answer |
|--------|--------|
| **Are quotes required?** | **YES** for `type="application/dicom"` |
| **Why?** | `/` is a tspecial character per RFC 2045 |
| **What if omitted?** | HTTP 415 error (Unsupported Media Type) |
| **Do all servers require it?** | Strict servers (Spring MVC, .NET) do; lenient parsers may accept both |
| **Industry practice?** | All major DICOMweb clients use quotes |
| **Recommendation?** | **Always use quotes** for maximum compatibility |

---

## Testing

See `test-content-type-quotes.sh` for automated testing of different Content-Type formats.

Run:
```bash
./test-content-type-quotes.sh
```

Expected results:
- ✅ `type="application/dicom"` → HTTP 200
- ❌ `type=application/dicom` → HTTP 415
- ✅ No type parameter → HTTP 200 (minimal format)
- ✅ Both parameters quoted → HTTP 200
