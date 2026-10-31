---
name: security-audit-skill
description: >-
  Comprehensive Automated Security & API Audit Runbook.
  Use to audit REST APIs, endpoints, authentication boundaries, tenant isolation,
  input handling, and OWASP Top 10 vulnerabilities across backend controllers and frontend consumers.
---

# Security Audit Skill

## Purpose
Executes a rigorous, end-to-end security and vulnerability audit of all application endpoints, API controllers, authorization gates, and data boundaries.

## Verification Checklist
1. **Endpoint Authorization**:
   - Every mutating route (`POST`, `PUT`, `DELETE`, `PATCH`) must enforce strict RBAC / tenant-scoped permissions (`@RequirePermission`).
   - Every reading route (`GET`) must enforce authenticated principal and tenant scoping (`TenantContextHolder` & `TenantQueryGuard`).
2. **Tenant Isolation (BOLA / IDOR)**:
   - No query may accept an entity UUID or ID without scoping it to the caller's `organizationId` / `tenant_id`.
3. **CSRF & Webhook Boundaries**:
   - Browser mutation endpoints must enforce `X-XSRF-TOKEN`.
   - Webhook exemptions must enforce cryptographic signature verification (HMAC).
4. **Input Sanitization & SQL Parameterization**:
   - Dynamic queries must bind parameters via `:param` in `NamedParameterJdbcTemplate` or JPA.
   - Text fields must not be concatenated directly into SQL or shell commands.
5. **Sensitive Data Redaction & Logging**:
   - PII, PHI, passwords, and tokens must be stripped before logging via `SecurityEventSanitizer`.
6. **Error Masking**:
   - Exception handlers must output normalized error envelopes without leaking database schematics, stack traces, or internal server IPs.
