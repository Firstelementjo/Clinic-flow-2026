/**
 * The tenant / project context.
 *
 * What this is: the project the operator is currently working in, and the set of
 * projects they may switch between. It is a *preference*, not an authorization:
 *
 * - The list comes from the server-resolved scope on the session
 *   (`SessionResponse.projectIds`), so a membership revoked a second ago is
 *   already absent — the client never assembles it from a URL or from storage.
 * - Choosing a project only changes the `projectId` query parameter a screen
 *   sends. `TenantQueryGuard` compares it against the caller's scope on the
 *   server and raises the same non-disclosing 404 for a foreign id as for a
 *   nonexistent one.
 *
 * `null` means "every project I can see", which is the server's own default and
 * not a wildcard the client invents.
 *
 * The stored preference is validated against the current scope on read. A
 * project id left in storage by a previous login, or by a membership that has
 * since been revoked, is discarded rather than sent — the failure that matters
 * is a screen that keeps asking for a project it may no longer see.
 */
import { isPlatformBrowser } from '@angular/common';
import { Injectable, PLATFORM_ID, computed, effect, inject, signal, untracked } from '@angular/core';
import { SessionStore } from '../auth/session.store';

const PROJECT_STORAGE_KEY = 'fe.project.v1';

@Injectable({ providedIn: 'root' })
export class ProjectContextService {
  private readonly sessions = inject(SessionStore);
  private readonly isBrowser = isPlatformBrowser(inject(PLATFORM_ID));

  private readonly selected = signal<string | null>(this.readStored());

  /** The chosen project, or `null` for the whole server-resolved scope. */
  readonly selectedProjectId = this.selected.asReadonly();
  readonly allowedProjectIds = this.sessions.projectIds;
  /** Whether more than one project is in scope, i.e. whether the switcher earns its place. */
  readonly hasChoice = computed(() => this.allowedProjectIds().length > 1);

  constructor() {
    // Seed from the stored preference once the session has resolved, then keep
    // dropping a selection the current scope does not contain. An `effect` rather
    // than a constructor read because the session resolves asynchronously.
    effect(() => {
      const allowed = this.allowedProjectIds();
      const current = untracked(this.selected);
      if (current !== null && !allowed.includes(current)) {
        this.selected.set(null);
        this.persist(null);
        return;
      }
      if (current === null && allowed.length > 0) {
        const stored = this.readStored();
        if (stored !== null && allowed.includes(stored)) {
          this.selected.set(stored);
          return;
        }
        this.persist(null);
      }
    });
  }

  select(projectId: string | null): void {
    const allowed = this.allowedProjectIds();
    // A project outside the resolved scope is not a selection this client will
    // make; the server would refuse it anyway, and a control that can set an
    // impossible value is a control that lies.
    if (projectId !== null && !allowed.includes(projectId)) return;
    this.selected.set(projectId);
    this.persist(projectId);
  }

  /** The query fragment every project-scoped list sends. Empty means "all". */
  asQueryParam(): { projectId?: string } {
    const projectId = this.selected();
    return projectId ? { projectId } : {};
  }

  /**
   * The display label for a project.
   *
   * `ProjectSummary` in the contract carries `projectId` and `archived` only —
   * there is no `name`, and no route in the document lists a project with one. So
   * the label is derived from the id, kept LTR inside Arabic prose, and the full
   * id is the accessible name. This is a contract gap, reported in the Phase 11
   * notes: inventing a project name would be inventing data.
   */
  shortLabel(projectId: string): string {
    return projectId.length <= 8 ? projectId : `…${projectId.slice(-8)}`;
  }

  private readStored(): string | null {
    if (!this.isBrowser) return null;
    try {
      const raw = globalThis.localStorage?.getItem(PROJECT_STORAGE_KEY) ?? null;
      return raw && raw !== '' ? raw : null;
    } catch {
      return null;
    }
  }

  private persist(projectId: string | null): void {
    if (!this.isBrowser) return;
    try {
      if (projectId === null) globalThis.localStorage?.removeItem(PROJECT_STORAGE_KEY);
      else globalThis.localStorage?.setItem(PROJECT_STORAGE_KEY, projectId);
    } catch {
      // A preference that cannot be stored still applies for this session.
    }
  }
}
