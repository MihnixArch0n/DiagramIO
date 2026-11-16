# Core Business Workflows

This document specifies the primary execution flows of DiagramIO. Standard CRUD actions (such as user registration, login, profile retrieval, password modification, diagram creation, and simple diagram deletion) are documented directly in [openapi.yaml](openapi.yaml) and the Swagger UI.

1. [Request Authentication Flow](#1-request-authentication-flow)
2. [Caller-Based Authorization](#2-caller-based-authorization)
3. [Token Refresh & Rotation](#3-token-refresh--rotation)
4. [Diagram Update (Optimistic Locking)](#4-diagram-update-optimistic-locking)
5. [Version Snapshot Creation](#5-version-snapshot-creation)
6. [Version Restoration](#6-version-restoration)
7. [Share Link Lifecycle](#7-share-link-lifecycle)

All API endpoints are prefixed with `/api/v1`. On failure, the service returns a standard JSON error envelope containing `timestamp`, `status`, `error`, `message`, and `path`. Data remains unchanged on errors.

| HTTP Code | Trigger Condition |
|---|---|
| **400** | Invalid payload: missing fields, malformed structure, `data` JSON invalid, `note` > 255 characters, `size` > 100 |
| **401** | Unauthenticated: missing/invalid/expired JWT or share token; invalid login credentials; reused/invalid refresh token; rejected on a diagram when the caller possesses neither an account nor a link |
| **402** | Payment Required (not used) |
| **403** | Forbidden: caller identity is recognized (authenticated user or valid share token) but lacks required permissions |
| **404** | Not Found: diagram, version, share link not found, or share token was revoked |
| **409** | Conflict: optimistic locking version mismatch, duplicate email or username |

---

## 1. Request Authentication Flow

**Scope:** Evaluated on every incoming HTTP request.

Each request passes through two security filters prior to reaching a controller:
1. `JwtAuthenticationFilter`: Extracts and validates `Authorization: Bearer <accessToken>`.
2. `ShareTokenFilter`: Extracts and resolves `X-Share-Token: <shareToken>`.

Neither filter unilaterally rejects requests; they populate authentication context. The endpoint's access rules in `SecurityConfig` decide whether unauthenticated calls result in `401 Unauthorized`. The resolved identity is encapsulated in a domain `Caller` object.

```mermaid
flowchart TD
    A([User sends request]) --> B{Has valid JWT?}
    B -- Yes --> C[Record userId]
    B -- No --> D{Has share token?}
    C --> D
    D -- Yes --> F[Record diagram & link permissions]
    D -- No --> H{Public endpoint?}
    F --> H
    H -- Yes --> K[Controller & service process with Caller]
    H -- No --> I{Caller identified?}
    I -- Yes --> K
    I -- No --> E1([401 Unauthorized])
    K --> Q{Authorized & valid data?}
    Q -- Yes --> OK([Success 200 / 201 / 204])
    Q -- No --> E2([Error 400 / 403 / 404 / 409])
```

- A valid JWT must possess a valid signature and must not be expired (15-minute TTL).
- A valid share token must be present in the `X-Share-Token` header and exist actively in `diagram_shares`.
- Public endpoints include: `/auth/register`, `/auth/login`, `/auth/refresh-token`, `/health`, `/shares/{token}`, `/diagrams/public`, and `GET /diagrams/{id}`. For `GET /diagrams/{id}`, unauthenticated callers reach the controller, and domain authorization decides if the diagram is publicly readable.
- If an authenticated user also supplies an `X-Share-Token`, both contexts merge into the `Caller`. Thus, an owner accessing their own share link retains owner privileges, and authenticated editors are tracked in `updated_by`.
- Share tokens are evaluated against the database on each request; revoking a link takes effect immediately on the next call.

---

## 2. Caller-Based Authorization

**Scope:** All operations targeting a specific diagram.

Authorization logic resides within `domain/access/Caller`. Domain services invoke `DiagramAccess.require(diagramId, caller, Permission)` (`VIEW`, `VIEW_HISTORY`, `EDIT`, `MANAGE`). Services do not differentiate whether a caller arrived via JWT or a share link.

```mermaid
flowchart TD
    A([User operates on diagram D]) --> O{Is owner of D?}
    O -- Yes --> ALL[Full Access<br/>Manage, Edit, Restore, Snapshot, View]
    O -- No --> L{Has share link for D?}
    L -- Yes, edit link --> ED[Edit Access<br/>View, View History, Edit, Snapshot, Restore]
    L -- Yes, view link --> VW[View Access<br/>View, View History]
    L -- No --> P{Is diagram D public?}
    P -- Yes --> PV[Public View<br/>View content only]
    P -- No --> NO([403 Forbidden])
```

- **Owner:** Full control. Can view, edit, snapshot, restore, delete the diagram, change `isPublic`, and manage share links.
- **Editor via Share Link:** Can view content, view version history, modify diagram data, create snapshots, and restore versions. Cannot delete diagram, change `isPublic`, or manage share links.
- **Viewer via Share Link:** Can view diagram content and view version history.
- **Public Caller:** Can view current diagram content only. Version history is restricted to prevent exposing historical drafts that may have contained private information prior to publishing.
- A share link generated for Diagram A conveys zero permissions for Diagram B.

---

## 3. Token Refresh & Rotation

**Endpoint:** `POST /api/v1/auth/refresh-token`

Access tokens expire in 15 minutes. Refresh tokens expire in 7 days and follow strict **single-use rotation**: each refresh invalidates the used refresh token and issues a new pair. Only the SHA-256 hash of refresh tokens is stored in the database.

```mermaid
flowchart TD
    A([User requests token refresh]) --> B{Has refreshToken in body?}
    B -- No --> E1([400 Bad Request])
    B -- Yes --> C[Query token by SHA-256 hash]
    C --> E{Token active & not expired?}
    E -- No --> E2([401 Unauthorized])
    E -- Yes --> F[Revoke old refresh token]
    F --> G[Issue new access token & rotated refresh token]
    G --> OK([Success 200 OK])
```

- Token lookup, revocation, and new token issuance execute inside an atomic database transaction via `TransactionRunner`.
- Submitting an already revoked or non-existent token yields `401 Unauthorized`.
- Logout (`POST /api/v1/auth/logout`) marks the submitted token `revoked = true`.

---

## 4. Diagram Update (Optimistic Locking)

**Endpoint:** `PUT /api/v1/diagrams/{id}`

Multiple collaborators may edit the same diagram. Clients must send the `version` number currently displayed on their canvas. If another collaborator persisted changes in the interim, the update is rejected with `409 Conflict` to prevent overwriting.

```mermaid
flowchart TD
    A([User submits diagram update]) --> B{Authenticated & valid format?}
    B -- No --> E1([400 Bad Request / 401 Unauthorized])
    B -- Yes --> C{Diagram exists & can edit?}
    C -- No --> E2([404 Not Found / 403 Forbidden])
    C -- Yes --> V{Client version matches DB?}
    V -- No --> E3([409 Conflict])
    V -- Yes --> J{Valid DiagramData structure & <= 2MB?}
    J -- No --> E4([400 Bad Request])
    J -- Yes --> S[Persist diagram & increment version]
    S --> R{Concurrent update conflict?}
    R -- Yes --> E5([409 Conflict])
    R -- No --> OK([Success 200 OK])
```

- Two layers protect against lost updates:
  1. Application-level check: `diagram.version() == request.version()`.
  2. JPA `@Version` concurrency check during SQL `UPDATE`: `WHERE id = ? AND version = ?`.
- `DiagramData` payload must represent a valid JSON object containing `nodes` and `edges` arrays and not exceed 2 MB.
- Only the owner may alter `isPublic`. If an editor via share link attempts to toggle `isPublic`, the existing value is preserved.
- When `409 Conflict` is returned, clients reload the latest diagram state via `GET /api/v1/diagrams/{id}` before re-applying edits.

---

## 5. Version Snapshot Creation

**Endpoint:** `POST /api/v1/diagrams/{id}/versions`

A snapshot captures an immutable copy of diagram `data` at a given point in time. Snapshots receive sequential numbers (1, 2, 3...) per diagram.

```mermaid
flowchart TD
    A([User saves version snapshot]) --> B{Valid note <= 255 chars?}
    B -- No --> E1([400 Bad Request])
    B -- Yes --> L[Lock diagram row<br/>SELECT ... FOR UPDATE]
    L --> C{Diagram exists & can edit?}
    C -- No --> E2([404 Not Found / 403 Forbidden])
    C -- Yes --> V{Optional version matches DB?}
    V -- No --> E3([409 Conflict])
    V -- Yes --> N[Compute next versionNumber = max + 1]
    N --> S[Save immutable snapshot in transaction]
    S --> OK([Success 201 Created])
```

- Executed within an atomic transaction with a pessimistic row lock (`SELECT ... FOR UPDATE`) on the target diagram. Concurrent snapshot requests on the same diagram queue up deterministically without sequence collisions.
- Database uniqueness constraint `UNIQUE (diagram_id, version_number)` serves as final data-integrity guard.
- The optional `version` field ensures users snapshot the exact state they intend rather than an interleaved update from another user.

---

## 6. Version Restoration

**Endpoint:** `POST /api/v1/diagrams/{id}/versions/{version_id}/restore`

Restoration replaces the diagram's active `data` with the content of a historical snapshot. The current state is automatically snapshotted first to guarantee reversibility.

```mermaid
flowchart TD
    A([User restores version]) --> L[Lock diagram row<br/>SELECT ... FOR UPDATE]
    L --> C{Diagram exists & can edit?}
    C -- No --> E1([404 Not Found / 403 Forbidden])
    C -- Yes --> F{Target version exists for diagram?}
    F -- No --> E2([404 Not Found])
    F -- Yes --> B[Auto-backup current state as new snapshot]
    B --> G[Copy snapshot data into diagram content]
    G --> S[Persist diagram & increment version]
    S --> OK([Success 200 OK])
```

- Both auto-backup creation and diagram overwrite execute inside a single transaction with pessimistic row locking (`SELECT ... FOR UPDATE`).
- Auto-backup notes follow the convention: `Auto backup before restoring version N`.
- Only `data` is restored; metadata (`title`, `diagramType`, `isPublic`) remains untouched. Historical snapshots are immutable and never modified or deleted.

---

## 7. Share Link Lifecycle

**Endpoints:**
- `POST /api/v1/diagrams/{id}/shares` (create link)
- `GET /api/v1/shares/{token}` (resolve link & retrieve diagram)
- `PATCH /api/v1/diagrams/{id}/shares/{share_id}` (change role)
- `DELETE /api/v1/diagrams/{id}/shares/{share_id}` (revoke link)

```mermaid
flowchart TD
    A([Owner creates share link with view or edit role]) --> B[Receive shareUrl & token]
    B --> D[Recipient accesses shareUrl / API]
    X[Owner updates role or revokes link] -. Immediately effective .-> E{Link active in DB?}
    D --> E
    E -- No --> ERR([404 Not Found])
    E -- Yes --> F[Call API endpoints with X-Share-Token]
    F --> R{Link role?}
    R -- view --> V[Read-only Access<br/>View Diagram, View History]
    R -- edit --> W[Editor Access<br/>View, Edit, Snapshot, Restore]
```

- Share tokens are cryptographically random 24-byte hex strings (48 characters).
- Only the diagram owner can create, modify, or revoke share links.
- Revocation permanently deletes the link from `diagram_shares`. Any subsequent requests using that token immediately fail with `404 Not Found`.
- Unauthenticated callers using a share link have `created_by` and `updated_by` recorded as `NULL`.
