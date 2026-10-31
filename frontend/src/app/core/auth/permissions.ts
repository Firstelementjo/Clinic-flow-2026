/**
 * The permission literals this client checks, in one place.
 *
 * ## Why they are declared here rather than inline at each call site
 *
 * Because getting one wrong is **silent**. `sessions.can()('conversation:write')`
 * compiles, runs, and returns `false` — so the composer never renders, the control is
 * simply absent, and the screen looks correct while being read-only for every user.
 *
 * That is not hypothetical. `conversation:write` and `customer:write` were both invented
 * during Phase 11 group 5, neither exists in the backend's `Permission` enum, and the
 * result was a matrix of journeys trying to type into a textarea and a notes field that
 * were not on the page. Nothing failed; the feature was just switched off.
 *
 * So the literals live here as a union, every call site is typed against it, and a typo
 * becomes a compile error rather than a silently disabled feature.
 *
 * ## Where the list comes from
 *
 * The `Permission` enum in the backend's identity module. The committed OpenAPI document
 * does not enumerate permissions, so the runtime source of truth is
 * `GET /api/v1/permissions/me` — and the Team screen's journey asserts that **every**
 * permission named here appears in the server's own answer for an administrator. That is
 * what stops the two lists drifting apart again, and it is an assertion about the real
 * server rather than about a copy of it.
 */
export const PERMISSIONS = {
  accountsRead: 'account:read',
  aiAgentConfigure: 'ai-agent:configure',
  aiAgentRead: 'ai-agent:read',
  aiRunRead: 'ai-run:read',
  aiSend: 'ai-send',
  appointmentRead: 'appointment:read',
  auditLogRead: 'audit-log:read',
  channelManage: 'channel:manage',
  channelRead: 'channel:read',
  /**
   * Create and edit physical clinic locations. ADMIN-only by default; the Clinics
   * screen gates its New/Save controls on it, and the server enforces it again.
   */
  clinicManage: 'clinic:manage',
  /** Create and edit service-catalogue entries. ADMIN-only by default. */
  procedureManage: 'procedure:manage',
  /** Assigning a conversation to somebody, including to oneself. */
  conversationAssign: 'conversation:assign',
  conversationRead: 'conversation:read',
  /** Replying in a thread. NOT `conversation:write`, which does not exist. */
  conversationReply: 'conversation:reply',
  /** Closing, reopening, and recording a case resolution. */
  conversationResolve: 'conversation:resolve',
  conversationReturnToAi: 'conversation:return-to-ai',
  /** Handing a conversation from the AI to a human. */
  conversationTakeOver: 'conversation:take-over',
  customerRead: 'customer:read',
  /** Editing a customer record. NOT `customer:write`, which does not exist. */
  customerUpdate: 'customer:update',
  doctorRead: 'doctor:read',
  /**
   * The management doctor projection. It discloses email and phone, which the
   * roster read does not, so the Doctors screen gates on this separate grant.
   */
  doctorReadManagement: 'doctor:read-management',
  /** Create, edit and retire a doctor profile. ADMIN-only by default. */
  doctorManage: 'doctor:manage',
  /** Read the front-desk directory. An account read, not the roster read. */
  frontDeskRead: 'front-desk:read',
  /** Create, edit and remove front-desk CLINIC_STAFF accounts. */
  frontDeskManage: 'front-desk:manage',
  employeeImport: 'employee:import',
  employeeRead: 'employee:read',
  employeeUpdate: 'employee:update',
  knowledgeManage: 'knowledge:manage',
  knowledgeRead: 'knowledge:read',
  membershipManage: 'membership:manage',
  /**
   * Finance and report figures for the whole organization.
   *
   * ## The one that matters, and why the distinction is not academic
   *
   * `FinanceController` guards **all twenty-four** of its routes — usage, forecast,
   * drill-through, rate cards, period close and lock, adjustment approval — with this
   * single permission, and its javadoc says so deliberately: *"There is no weaker
   * permission for 'what does this cost'."* Its `FinanceRoutePermissionTest` asserts
   * by reflection that no handler is left unguarded.
   *
   * So a Finance screen is not a screen a "reports" role can reach. It is an
   * organization-wide revenue, cost and margin surface, and the client gates the whole
   * section on this one value.
   */
  analyticsReadOrganization: 'analytics:read-organization',
  /** Report and export data scoped to the selected project. Weaker, and deliberately so. */
  analyticsReadProject: 'analytics:read-project',
  employeeCreate: 'employee:create',
  employeeDelete: 'employee:delete',
  employeeSetOwnAvailability: 'employee:set-own-availability',
  escalationRuleManage: 'escalation-rule:manage',
  escalationRuleRead: 'escalation-rule:read',
  organizationRead: 'organization:read',
  /**
   * Destructive, irreversible governance writes.
   *
   * The backend reuses this one for legal holds, privacy-deletion requests and their
   * execution, purge, export-artefact deletion and export retention expiry/purge. It is
   * the broadest write in the system, and a single permission across all of them is
   * **Hook 12** — see the phase report. The client still treats it as destructive and
   * confirms each one by name.
   */
  organizationUpdate: 'organization:update',
  projectArchive: 'project:archive',
  projectCreate: 'project:create',
  projectDelete: 'project:delete',
  projectUpdate: 'project:update',
  routingRuleManage: 'routing-rule:manage',
  /**
   * Authoring a report definition or a document template, and publishing or retiring
   * their versions.
   *
   * ## Why this is its own permission and not `workflow:manage`
   *
   * The thirteen authoring routes under `/api/v1/exports` were gated on a borrowed
   * `WORKFLOW_MANAGE` until phase 31, which gave them a `REPORT_MANAGE` of their own.
   * The borrow was wrong for a reason the client should not paper over: a workflow rule
   * and a report definition have nothing in common except that someone needed a permission
   * to gate on. Holding `workflow:manage` now says nothing about whether a user may author
   * exports, and holding `report:manage` says nothing about workflow — so a screen that
   * gated its authoring controls on `workflowManage` would have been **wrong in both
   * directions**, offering authoring to workflow administrators and withholding it from
   * the people whose job it is.
   *
   * `workflowManage` stays in the vocabulary because the workflow routes still use it;
   * this addition does not migrate anything, it names what already changed.
   *
   * ## The server is the boundary
   *
   * This is what the client gates the authoring controls on, and it is a UX gate only. A
   * `report:manage` holder is refused by the server for any definition that is not theirs,
   * and a non-holder is refused everywhere — the client's opinion changes what is drawn,
   * never what is permitted.
   *
   * ## Why the contract cannot confirm this string
   *
   * `report:manage` appears **nowhere** in the committed OpenAPI document — not as an enum,
   * not in a description. Neither does `workflow:manage` or `sla:manage`, and the client
   * already carries all of them. So there is no drift assertion to make here and no
   * document to check the spelling against: the permission vocabulary is a client-side
   * mirror of the backend's constants, kept by hand, and the honest statement is that this
   * literal is **unverified by any committed artefact**. See Hook 21 in the phase report.
   */
  reportManage: 'report:manage',
  workflowManage: 'workflow:manage',
  noteCreate: 'note:create',
  noteDelete: 'note:delete',
  noteRead: 'note:read',
  noteUpdate: 'note:update',
  permissionInspectorRead: 'permission-inspector:read',
  procedureRead: 'procedure:read',
  projectRead: 'project:read',
  securityEventRead: 'security-event:read',
  sessionRevoke: 'session:revoke',
  slaManage: 'sla:manage',
  systemHealthRead: 'system-health:read',
  userManage: 'user:manage',
} as const;

/** The union the `can()` helper accepts, so a typo will not compile. */
export type PermissionName = (typeof PERMISSIONS)[keyof typeof PERMISSIONS];

/** Every permission literal this client knows about, for the drift assertion. */
export const ALL_PERMISSIONS: readonly string[] = Object.values(PERMISSIONS);
