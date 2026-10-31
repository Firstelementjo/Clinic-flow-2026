/**
 * `/app/clinic/reports` — Dental Clinic Reports, Audit Stream & Access Control Hardening (Slice S8).
 * Complies with ADR V4 multi-tenancy, design system (0 hex/rgba), 0 digits in static dicts, 0 emojis.
 */

import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ProjectContextService } from '../../core/context/project-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { AsyncStateComponent } from '../../ui/async-state.component';
import {
  ClinicPatientApi,
  type AppointmentProductivityRow,
  type ProcedureUtilizationRow,
  type FinancialCollectionSummaryRow,
  type PatientRetentionRecallRow,
  type BreakGlassComplianceRow,
  type BreakGlassGrantRow,
  type ClinicAuditRow,
  type ClinicAuditExportView,
} from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';

type ReportTab = 'productivity' | 'procedures' | 'financials' | 'recall' | 'compliance' | 'audit';

@Component({
  selector: 'fe-clinic-reports-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    CommonModule,
    FormsModule,
    RouterLink,
    TranslatePipe,
  ],
  template: `
    <div class="fe-page tl-page" data-testid="clinic-reports-s8">
      <!-- ToothLogic Breadcrumbs & Header -->
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">Home</a>
        <span>»</span>
        <span class="active">Reports</span>
      </div>

      <div class="tl-toolbar" style="margin-bottom: 0;">
        <h1 class="tl-greeting" style="font-size: 20px; font-weight: 700; margin: 0;">Commission/Revenue</h1>
        <div style="display: flex; align-items: center; gap: 8px;">
          <input
            type="date"
            class="tl-date-input"
            [ngModel]="reportDate()"
            (ngModelChange)="onReportDateChange($event)"
          />
          <button type="button" class="tl-btn tl-btn-teal" (click)="loadCurrentTab()" title="Reload">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
              <path d="M23 4v6h-6"></path>
              <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path>
            </svg>
          </button>
          <button
            type="button"
            class="tl-btn tl-btn-outline"
            data-testid="export-report-btn"
            (click)="exportCurrentReport()"
          >
            <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
              <polyline points="7 10 12 15 17 10"></polyline>
              <line x1="12" y1="15" x2="12" y2="3"></line>
            </svg>
            {{ 'clinic.reports.export' | t }}
          </button>
        </div>
      </div>

      <!-- 5 Summary KPI Cards matching Tooth-Logic-Billing-Payouts-1536x1095.png -->
      <div class="tl-stat-row">
        <div class="tl-stat-card">
          <div class="tl-stat-left">
            <span class="tl-stat-title">Total Appointments</span>
            <span class="tl-stat-value">05</span>
          </div>
          <div class="tl-stat-icon calendar">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect>
              <line x1="16" y1="2" x2="16" y2="6"></line>
              <line x1="8" y1="2" x2="8" y2="6"></line>
              <line x1="3" y1="10" x2="21" y2="10"></line>
            </svg>
          </div>
        </div>

        <div class="tl-stat-card">
          <div class="tl-stat-left">
            <span class="tl-stat-title">Doctor's Commission</span>
            <span class="tl-stat-value">300 JOD</span>
          </div>
          <div class="tl-stat-icon service">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="3"></circle>
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
            </svg>
          </div>
        </div>

        <div class="tl-stat-card">
          <div class="tl-stat-left">
            <span class="tl-stat-title">Admin Commission</span>
            <span class="tl-stat-value">200 JOD</span>
          </div>
          <div class="tl-stat-icon doctor">
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
            </svg>
          </div>
        </div>

        <div class="tl-stat-card">
          <div class="tl-stat-left">
            <span class="tl-stat-title">Total Clinic Revenue</span>
            <span class="tl-stat-value">900 JOD</span>
          </div>
          <div class="tl-stat-icon clinic">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 21h18M5 21V7l8-4 8 4v14M10 9v4M14 9v4"></path>
            </svg>
          </div>
        </div>

        <div class="tl-stat-card">
          <div class="tl-stat-left">
            <span class="tl-stat-title">Remaining Balance</span>
            <span class="tl-stat-value">400 JOD</span>
          </div>
          <div class="tl-stat-icon revenue">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="8" cy="8" r="6"/>
              <path d="M18.09 10.37A6 6 0 1 1 10.34 18M7 6h2v4H7z"/>
            </svg>
          </div>
        </div>
      </div>

      <!-- Active Emergency Break-Glass Banner -->
      @if (activeGrants().length > 0) {
        <div class="break-glass-banner" data-testid="active-break-glass-banner">
          <div class="banner-content">
            <span class="banner-badge">{{ 'clinic.breakGlass.banner' | t }}</span>
            <span class="banner-desc">{{ 'clinic.breakGlass.bannerDesc' | t }}</span>
          </div>
          <div class="banner-grants">
            @for (grant of activeGrants(); track grant.id) {
              <div class="grant-pill">
                <span class="grant-reason">{{ grant.reason }}</span>
                <span class="grant-time">{{ grant.expiresAt | date:'shortTime' }}</span>
                <button
                  class="pill-btn"
                  data-testid="revoke-grant-pill-btn"
                  (click)="openRevokeModal(grant.id)"
                >
                  {{ 'clinic.breakGlass.revokeTitle' | t }}
                </button>
              </div>
            }
          </div>
        </div>
      }

      <!-- Export Signed Notification Banner -->
      @if (exportResult(); as exp) {
        <div class="export-banner" data-testid="export-signed-banner">
          <div class="export-banner-header">
            <strong>{{ 'clinic.reports.exportSuccess' | t }}</strong>
            <button class="close-btn" (click)="dismissExport()">×</button>
          </div>
          <div class="export-details">
            <span>{{ 'clinic.audit.tamperHash' | t }}: <code>{{ exp.exportSignature }}</code></span>
            <span>{{ 'clinic.audit.timestamp' | t }}: {{ exp.generatedAt | date:'medium' }}</span>
          </div>
        </div>
      }

      <!-- Navigation Tabs & Parameter Filters Bar -->
      <div class="reports-subnav-bar">
        <nav class="report-nav" aria-label="Reports sub-navigation">
          <button
            class="report-nav-btn"
            [class.active]="activeTab() === 'productivity'"
            data-testid="tab-productivity"
            (click)="setTab('productivity')"
          >
            {{ 'clinic.reports.tabProductivity' | t }}
          </button>
          <button
            class="report-nav-btn"
            [class.active]="activeTab() === 'procedures'"
            data-testid="tab-procedures"
            (click)="setTab('procedures')"
          >
            {{ 'clinic.reports.tabProcedures' | t }}
          </button>
          <button
            class="report-nav-btn"
            [class.active]="activeTab() === 'financials'"
            data-testid="tab-financials"
            (click)="setTab('financials')"
          >
            {{ 'clinic.reports.tabFinancials' | t }}
          </button>
          <button
            class="report-nav-btn"
            [class.active]="activeTab() === 'recall'"
            data-testid="tab-recall"
            (click)="setTab('recall')"
          >
            {{ 'clinic.reports.tabRecall' | t }}
          </button>
          <button
            class="report-nav-btn"
            [class.active]="activeTab() === 'compliance'"
            data-testid="tab-compliance"
            (click)="setTab('compliance')"
          >
            {{ 'clinic.reports.tabCompliance' | t }}
          </button>
          <button
            class="report-nav-btn"
            [class.active]="activeTab() === 'audit'"
            data-testid="tab-audit"
            (click)="setTab('audit')"
          >
            {{ 'clinic.audit.title' | t }}
          </button>
        </nav>

        <!-- Parameter Filter Controls -->
        <div class="filter-bar">
          <div class="window-switcher" data-testid="window-switcher">
            <button
              class="window-btn"
              [class.active]="selectedWindow() === '7d'"
              (click)="setWindow('7d')"
            >
              {{ 'clinic.reports.rangeWeek' | t }}
            </button>
            <button
              class="window-btn"
              [class.active]="selectedWindow() === '30d'"
              (click)="setWindow('30d')"
            >
              {{ 'clinic.reports.rangeMonth' | t }}
            </button>
            <button
              class="window-btn"
              [class.active]="selectedWindow() === '90d'"
              (click)="setWindow('90d')"
            >
              {{ 'clinic.reports.rangeQuarter' | t }}
            </button>
            <button
              class="window-btn"
              [class.active]="selectedWindow() === 'year'"
              (click)="setWindow('year')"
            >
              {{ 'clinic.reports.rangeYear' | t }}
            </button>
          </div>

          <button
            class="tl-btn tl-btn-outline"
            style="height: 28px; padding: 0 10px; font-size: 0.78rem;"
            (click)="loadCurrentTab()"
          >
            {{ 'clinic.refresh' | t }}
          </button>
        </div>
      </div>

      <!-- Loading / Error States -->
      <fe-async-state
        [loading]="loading()"
        [error]="error()"
        [hasValue]="hasData()"
        [loadingLabel]="'state.loading' | t"
        [loadingHint]="'state.loadingHint' | t"
        [errorTitle]="'clinic.error.title' | t"
        [errorBody]="'clinic.error.body' | t"
        [retryLabel]="'clinic.retry' | t"
        (retry)="loadCurrentTab()"
      />

      <!-- TAB 1: Clinician Productivity -->
      @if (activeTab() === 'productivity' && !loading()) {
        <section class="report-panel" data-testid="panel-productivity">
          <div class="table-container">
            <table class="fe-table" data-testid="productivity-table">
              <thead>
                <tr>
                  <th>{{ 'clinic.reports.doctor' | t }}</th>
                  <th>{{ 'clinic.reports.specialty' | t }}</th>
                  <th>{{ 'clinic.reports.totalAppointments' | t }}</th>
                  <th>{{ 'clinic.reports.completedAppointments' | t }}</th>
                  <th>{{ 'clinic.reports.cancelledAppointments' | t }}</th>
                  <th>{{ 'clinic.reports.noshowAppointments' | t }}</th>
                  <th>{{ 'clinic.reports.completionRate' | t }}</th>
                  <th>{{ 'clinic.reports.totalBilled' | t }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of productivityRows(); track row.doctorId) {
                  <tr>
                    <td class="font-bold">{{ row.doctorName }}</td>
                    <td>{{ row.specialty || 'General' }}</td>
                    <td>{{ row.totalAppointments }}</td>
                    <td class="text-success font-bold">{{ row.completedAppointments }}</td>
                    <td class="text-danger">{{ row.cancelledAppointments }}</td>
                    <td class="text-warning">{{ row.noShowAppointments }}</td>
                    <td>{{ row.completionRatePercent }}%</td>
                    <td class="font-bold">{{ row.totalInvoiced | number:'1.3-3' }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="8" class="text-empty">{{ 'clinic.reports.empty' | t }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }

      <!-- TAB 2: Procedure Utilization -->
      @if (activeTab() === 'procedures' && !loading()) {
        <section class="report-panel" data-testid="panel-procedures">
          <div class="table-container">
            <table class="fe-table" data-testid="procedures-table">
              <thead>
                <tr>
                  <th>{{ 'clinic.reports.procedureCode' | t }}</th>
                  <th>{{ 'clinic.reports.procedureName' | t }}</th>
                  <th>{{ 'clinic.reports.category' | t }}</th>
                  <th>{{ 'clinic.reports.procedureCount' | t }}</th>
                  <th>{{ 'clinic.reports.revenue' | t }}</th>
                  <th>{{ 'clinic.reports.averagePrice' | t }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of procedureRows(); track row.procedureCode) {
                  <tr>
                    <td><code>{{ row.procedureCode }}</code></td>
                    <td class="font-bold">{{ row.procedureName }}</td>
                    <td><span class="category-pill">{{ row.category }}</span></td>
                    <td class="font-bold">{{ row.procedureCount }}</td>
                    <td class="text-success font-bold">{{ row.totalRevenue | number:'1.3-3' }}</td>
                    <td>{{ row.averagePrice | number:'1.3-3' }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="6" class="text-empty">{{ 'clinic.reports.empty' | t }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }

      <!-- TAB 3: Financial Collections -->
      @if (activeTab() === 'financials' && !loading()) {
        <section class="report-panel" data-testid="panel-financials">
          <div class="financials-grid">
            @for (fin of financialRows(); track fin.currency) {
              <div class="currency-card">
                <div class="currency-header">
                  <span class="currency-tag">{{ fin.currency }}</span>
                  <span class="subtext">{{ fin.invoiceCount }} / {{ fin.paymentCount }}</span>
                </div>
                <div class="kpi-columns">
                  <div class="kpi-item">
                    <span class="kpi-label">{{ 'clinic.reports.invoiced' | t }}</span>
                    <span class="kpi-value">{{ fin.totalInvoiced | number:'1.3-3' }}</span>
                  </div>
                  <div class="kpi-item">
                    <span class="kpi-label text-success">{{ 'clinic.reports.collected' | t }}</span>
                    <span class="kpi-value text-success font-bold">{{ fin.totalCollected | number:'1.3-3' }}</span>
                  </div>
                  <div class="kpi-item">
                    <span class="kpi-label text-danger">{{ 'clinic.reports.outstanding' | t }}</span>
                    <span class="kpi-value text-danger font-bold">{{ fin.totalOutstanding | number:'1.3-3' }}</span>
                  </div>
                  <div class="kpi-item">
                    <span class="kpi-label">{{ 'clinic.reports.collectionRate' | t }}</span>
                    <span class="kpi-value text-primary font-bold">{{ fin.collectionRatePercent }}%</span>
                  </div>
                </div>
              </div>
            } @empty {
              <p class="text-empty">{{ 'clinic.reports.empty' | t }}</p>
            }
          </div>
        </section>
      }

      <!-- TAB 4: Patient Recall & Retention -->
      @if (activeTab() === 'recall' && !loading()) {
        <section class="report-panel" data-testid="panel-recall">
          <div class="table-container">
            <table class="fe-table" data-testid="recall-table">
              <thead>
                <tr>
                  <th>{{ 'clinic.reports.patient' | t }}</th>
                  <th>{{ 'clinic.reports.phone' | t }}</th>
                  <th>{{ 'clinic.reports.lastVisit' | t }}</th>
                  <th>{{ 'clinic.reports.daysOverdue' | t }}</th>
                  <th>{{ 'clinic.reports.recallStatus' | t }}</th>
                  <th>{{ 'clinic.reports.recommendedProcedure' | t }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of recallRows(); track row.patientId) {
                  <tr>
                    <td class="font-bold">{{ row.patientName }}</td>
                    <td>{{ row.phone || '—' }}</td>
                    <td>{{ row.lastVisitDate ? (row.lastVisitDate | date:'mediumDate') : '—' }}</td>
                    <td class="font-bold">{{ row.daysSinceLastVisit }}</td>
                    <td>
                      <span
                        class="status-pill"
                        [class.status-overdue]="row.recallStatus === 'OVERDUE'"
                        [class.status-due]="row.recallStatus === 'DUE_SOON'"
                        [class.status-ok]="row.recallStatus === 'UP_TO_DATE'"
                      >
                        {{ row.recallStatus === 'OVERDUE' ? ('clinic.reports.recallStatusOverdue' | t) :
                           row.recallStatus === 'DUE_SOON' ? ('clinic.reports.recallStatusDueSoon' | t) :
                           ('clinic.reports.recallStatusUpToDate' | t) }}
                      </span>
                    </td>
                    <td>{{ row.recommendedProcedure }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="6" class="text-empty">{{ 'clinic.reports.empty' | t }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }

      <!-- TAB 5: Break-Glass Compliance & Review Queue -->
      @if (activeTab() === 'compliance' && !loading()) {
        <section class="report-panel" data-testid="panel-compliance">
          <div class="table-container">
            <table class="fe-table" data-testid="compliance-table">
              <thead>
                <tr>
                  <th>{{ 'clinic.breakGlass.requester' | t }}</th>
                  <th>{{ 'clinic.breakGlass.patient' | t }}</th>
                  <th>{{ 'clinic.breakGlass.reasonLabel' | t }}</th>
                  <th>{{ 'clinic.breakGlass.grantedAt' | t }}</th>
                  <th>{{ 'clinic.breakGlass.expiresAt' | t }}</th>
                  <th>{{ 'clinic.breakGlass.reviewStatus' | t }}</th>
                  <th>{{ 'clinic.breakGlass.reviewNotesLabel' | t }}</th>
                  <th>{{ 'clinic.media.auditAction' | t }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of complianceRows(); track row.grantId) {
                  <tr>
                    <td class="font-bold">{{ row.requesterName }}</td>
                    <td>{{ row.patientName }}</td>
                    <td>{{ row.reason }}</td>
                    <td>{{ row.grantedAt | date:'short' }}</td>
                    <td>{{ row.expiresAt | date:'short' }}</td>
                    <td>
                      <span
                        class="status-pill"
                        [class.status-due]="row.reviewStatus === 'PENDING'"
                        [class.status-ok]="row.reviewStatus === 'APPROVED'"
                        [class.status-overdue]="row.reviewStatus === 'FLAGGED'"
                      >
                        {{ row.reviewStatus }}
                      </span>
                    </td>
                    <td>{{ row.reviewNotes || '—' }}</td>
                    <td>
                      @if (row.reviewStatus === 'PENDING') {
                        <div class="action-btn-group">
                          <button
                            class="action-btn approve"
                            data-testid="approve-compliance-btn"
                            (click)="reviewGrant(row.grantId, 'APPROVED', 'Clinical emergency protocol verified.')"
                          >
                            {{ 'clinic.breakGlass.approveAction' | t }}
                          </button>
                          <button
                            class="action-btn flag"
                            data-testid="flag-compliance-btn"
                            (click)="reviewGrant(row.grantId, 'FLAGGED', 'Non-compliant emergency access justification.')"
                          >
                            {{ 'clinic.breakGlass.flagAction' | t }}
                          </button>
                        </div>
                      } @else {
                        <span class="text-muted">{{ row.reviewerName || 'Audited' }}</span>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="8" class="text-empty">{{ 'clinic.reports.empty' | t }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }

      <!-- TAB 6: Immutable Audit Stream -->
      @if (activeTab() === 'audit' && !loading()) {
        <section class="report-panel" data-testid="panel-audit">
          <div class="audit-chain-bar">
            <button
              class="fe-btn fe-btn--secondary"
              data-testid="verify-chain-btn"
              (click)="verifyChainIntegrity()"
            >
              {{ 'clinic.audit.verifyChain' | t }}
            </button>
            @if (chainVerified() !== null) {
              <span
                class="chain-badge"
                [class.valid]="chainVerified()"
                [class.invalid]="!chainVerified()"
                data-testid="chain-status-badge"
              >
                {{ chainVerified() ? ('clinic.audit.chainValid' | t) : ('clinic.audit.chainInvalid' | t) }}
              </span>
            }
          </div>

          <div class="table-container">
            <table class="fe-table" data-testid="audit-table">
              <thead>
                <tr>
                  <th>{{ 'clinic.audit.timestamp' | t }}</th>
                  <th>{{ 'clinic.audit.actor' | t }}</th>
                  <th>{{ 'clinic.audit.role' | t }}</th>
                  <th>{{ 'clinic.audit.action' | t }}</th>
                  <th>{{ 'clinic.audit.resource' | t }}</th>
                  <th>{{ 'clinic.audit.tamperHash' | t }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of auditRows(); track row.id) {
                  <tr>
                    <td>{{ row.occurredAt | date:'medium' }}</td>
                    <td class="font-bold">{{ row.actorUserName }}</td>
                    <td><span class="role-pill">{{ row.actorRole }}</span></td>
                    <td><code>{{ row.action }}</code></td>
                    <td>{{ row.resourceType }}: {{ row.resourceId }}</td>
                    <td><code class="hash-text">{{ row.tamperHash }}</code></td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="6" class="text-empty">{{ 'clinic.reports.empty' | t }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }

      <!-- Break-Glass Request Modal -->
      @if (showBreakGlassModal()) {
        <div class="modal-backdrop" role="dialog" aria-modal="true" data-testid="break-glass-modal">
          <div class="modal-card">
            <div class="modal-header">
              <h3>{{ 'clinic.breakGlass.requestTitle' | t }}</h3>
              <button class="close-btn" (click)="closeBreakGlassModal()">×</button>
            </div>
            <div class="modal-body">
              <div class="form-group">
                <label for="bg-reason">{{ 'clinic.breakGlass.reasonLabel' | t }} *</label>
                <textarea
                  id="bg-reason"
                  class="fe-textarea"
                  rows="3"
                  [(ngModel)]="breakGlassReason"
                  [placeholder]="'clinic.breakGlass.reasonPlaceholder' | t"
                  data-testid="break-glass-reason-input"
                ></textarea>
              </div>
              <div class="form-group">
                <label for="bg-duration">{{ 'clinic.breakGlass.durationLabel' | t }}</label>
                <select
                  id="bg-duration"
                  class="fe-select"
                  [(ngModel)]="breakGlassDuration"
                  data-testid="break-glass-duration-select"
                >
                  <option [value]="4">{{ 'clinic.breakGlass.durationFourHours' | t }}</option>
                  <option [value]="8">{{ 'clinic.breakGlass.durationEightHours' | t }}</option>
                  <option [value]="12">{{ 'clinic.breakGlass.durationTwelveHours' | t }}</option>
                </select>
              </div>
              @if (breakGlassError()) {
                <div class="modal-error" data-testid="break-glass-error">
                  {{ breakGlassError() }}
                </div>
              }
            </div>
            <div class="modal-footer">
              <button
                class="fe-btn fe-btn--secondary"
                (click)="closeBreakGlassModal()"
              >
                {{ 'clinic.media.viewer.close' | t }}
              </button>
              <button
                class="fe-btn fe-btn--danger"
                data-testid="submit-break-glass-btn"
                [disabled]="submitting()"
                (click)="submitBreakGlass()"
              >
                {{ 'clinic.breakGlass.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Break-Glass Revoke Modal -->
      @if (showRevokeModal()) {
        <div class="modal-backdrop" role="dialog" aria-modal="true" data-testid="revoke-modal">
          <div class="modal-card">
            <div class="modal-header">
              <h3>{{ 'clinic.breakGlass.revokeTitle' | t }}</h3>
              <button class="close-btn" (click)="closeRevokeModal()">×</button>
            </div>
            <div class="modal-body">
              <div class="form-group">
                <label for="revoke-reason">{{ 'clinic.breakGlass.revokeReasonLabel' | t }}</label>
                <input
                  id="revoke-reason"
                  type="text"
                  class="fe-input"
                  [(ngModel)]="revokeReason"
                  data-testid="revoke-reason-input"
                />
              </div>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--secondary" (click)="closeRevokeModal()">
                {{ 'clinic.media.viewer.close' | t }}
              </button>
              <button
                class="fe-btn fe-btn--danger"
                data-testid="confirm-revoke-btn"
                [disabled]="submitting()"
                (click)="submitRevoke()"
              >
                {{ 'clinic.breakGlass.revokeSubmit' | t }}
              </button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    :host {
      display: block;
      height: 100%;
      overflow: hidden;
    }
    .fe-page.tl-page {
      padding: 8px 16px;
      height: 100%;
      max-height: calc(100dvh - 52px);
      display: flex;
      flex-direction: column;
      overflow: hidden;
      box-sizing: border-box;
      gap: 6px;
    }
    .tl-stat-row {
      display: grid;
      grid-template-columns: repeat(5, minmax(0, 1fr));
      gap: 8px;
      flex-shrink: 0;
    }
    @media (max-width: 900px) {
      .tl-stat-row {
        grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      }
    }
    .tl-stat-card {
      padding: 6px 10px;
      height: 46px;
      background: var(--surf);
      border: 1px solid var(--line);
      border-radius: 6px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 6px;
    }
    .tl-stat-left {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .tl-stat-title {
      font-size: 10.5px;
      color: var(--mute);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .tl-stat-value {
      font-size: 15px !important;
      font-weight: 700;
      color: var(--ink);
      line-height: 1.2;
    }
    .tl-stat-icon {
      width: 26px;
      height: 26px;
      border-radius: 6px;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .tl-stat-icon svg {
      width: 14px;
      height: 14px;
    }
    .reports-subnav-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      flex-wrap: wrap;
      border-bottom: 1px solid var(--line);
      padding-bottom: 2px;
      flex-shrink: 0;
    }
    .report-nav {
      display: flex;
      align-items: center;
      gap: 4px;
      overflow-x: auto;
      overflow-y: hidden;
      scrollbar-width: none;
      -ms-overflow-style: none;
      border-bottom: none;
      margin-block-end: 0;
    }
    .report-nav::-webkit-scrollbar {
      display: none;
    }
    .report-nav-btn {
      border: none;
      background: none;
      padding: 5px 8px;
      font-size: 0.8rem;
      font-weight: 600;
      color: var(--mute);
      cursor: pointer;
      border-bottom: 2px solid transparent;
      white-space: nowrap;
      transition: all 0.15s ease;
    }
    .report-nav-btn:hover { color: var(--ink); }
    .report-nav-btn.active { color: var(--tl-teal, var(--pri)); border-bottom-color: var(--tl-teal, var(--pri)); font-weight: 700; }
    .filter-bar {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-block-end: 0;
    }
    .window-switcher { display: flex; gap: 2px; background: var(--surf2); padding: 2px; border-radius: 4px; }
    .window-btn { border: none; background: none; padding: 3px 7px; border-radius: 3px; font-size: 0.75rem; cursor: pointer; color: var(--mute); font-weight: 500; }
    .window-btn.active { background: var(--surf); color: var(--tl-teal, var(--pri)); font-weight: 700; box-shadow: var(--shadow); }
    .report-panel {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      margin-block-end: 0;
    }
    .table-container {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      overflow-x: auto;
      background: var(--surf);
      border-radius: var(--fe-radius);
      border: 1px solid var(--line);
    }
    .fe-table { width: 100%; border-collapse: collapse; text-align: start; }
    .fe-table th, .fe-table td { padding: 6px 10px; border-bottom: 1px solid var(--line); font-size: 0.8rem; }
    .fe-table th { background: var(--surf2); font-weight: 600; color: var(--mute); position: sticky; top: 0; z-index: 2; }
    .header-actions { display: flex; gap: var(--fe-space-2); align-items: center; }
    .break-glass-banner {
      background: var(--bad-soft);
      border: 1px solid var(--bad);
      border-radius: var(--fe-radius);
      padding: var(--fe-space-3);
      margin-block-end: var(--fe-space-3);
      display: flex;
      flex-direction: column;
      gap: var(--fe-space-2);
    }
    .banner-content { display: flex; align-items: center; gap: var(--fe-space-2); }
    .banner-badge {
      background: var(--bad);
      color: var(--surf);
      font-weight: 700;
      padding: 0.15rem 0.5rem;
      border-radius: 0.25rem;
      font-size: 0.8rem;
    }
    .banner-desc { font-size: 0.875rem; color: var(--ink); font-weight: 500; }
    .banner-grants { display: flex; flex-wrap: wrap; gap: var(--fe-space-2); }
    .grant-pill {
      background: var(--surf);
      border: 1px solid var(--bad);
      padding: 0.25rem 0.5rem;
      border-radius: 0.25rem;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-size: 0.8rem;
    }
    .grant-reason { font-weight: 600; color: var(--bad); }
    .grant-time { color: var(--mute); }
    .pill-btn {
      border: none;
      background: var(--bad-soft);
      color: var(--bad);
      font-size: 0.75rem;
      padding: 0.15rem 0.4rem;
      border-radius: 0.2rem;
      cursor: pointer;
      font-weight: 600;
    }
    .pill-btn:hover { background: var(--bad); color: var(--surf); }
    .export-banner {
      background: var(--ok-soft);
      border: 1px solid var(--ok);
      border-radius: var(--fe-radius);
      padding: var(--fe-space-3);
      margin-block-end: var(--fe-space-3);
    }
    .export-banner-header { display: flex; justify-content: space-between; align-items: center; color: var(--ok); }
    .export-details { display: flex; gap: var(--fe-space-4); margin-top: 0.25rem; font-size: 0.8rem; color: var(--ink); }
    .export-details code { background: var(--surf); padding: 0.1rem 0.3rem; border-radius: 0.2rem; }
    .category-pill, .role-pill {
      background: var(--surf2);
      padding: 0.15rem 0.5rem;
      border-radius: 0.25rem;
      font-size: 0.75rem;
      font-weight: 600;
      color: var(--ink);
    }
    .financials-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: var(--fe-space-3); }
    .currency-card { background: var(--surf); border: 1px solid var(--line); border-radius: var(--fe-radius); padding: var(--fe-space-3); }
    .currency-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; border-bottom: 1px solid var(--line); padding-bottom: 0.5rem; }
    .currency-tag { font-size: 1.25rem; font-weight: 800; color: var(--pri); background: var(--pri-soft); padding: 0.15rem 0.5rem; border-radius: 0.25rem; }
    .currency-header .subtext { font-size: 0.85rem; color: var(--mute); }
    .kpi-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
    .kpi-item { display: flex; flex-direction: column; gap: 0.25rem; }
    .kpi-label { font-size: 0.8rem; color: var(--mute); }
    .kpi-value { font-size: 1.25rem; }
    .status-pill { padding: 0.2rem 0.5rem; border-radius: 0.25rem; font-size: 0.75rem; font-weight: 700; display: inline-block; }
    .status-overdue { background: var(--bad-soft); color: var(--bad); }
    .status-due { background: var(--warn-soft); color: var(--warn); }
    .status-ok { background: var(--ok-soft); color: var(--ok); }
    .action-btn-group { display: flex; gap: 0.25rem; }
    .action-btn { border: none; padding: 0.25rem 0.5rem; border-radius: 0.25rem; font-size: 0.75rem; font-weight: 600; cursor: pointer; }
    .action-btn.approve { background: var(--ok-soft); color: var(--ok); }
    .action-btn.approve:hover { background: var(--ok); color: var(--surf); }
    .action-btn.flag { background: var(--bad-soft); color: var(--bad); }
    .action-btn.flag:hover { background: var(--bad); color: var(--surf); }
    .audit-chain-bar { display: flex; align-items: center; gap: 1rem; margin-bottom: 1rem; }
    .chain-badge { padding: 0.3rem 0.6rem; border-radius: var(--fe-radius); font-size: 0.85rem; font-weight: 600; }
    .chain-badge.valid { background: var(--ok-soft); color: var(--ok); border: 1px solid var(--ok); }
    .chain-badge.invalid { background: var(--bad-soft); color: var(--bad); border: 1px solid var(--bad); }
    .hash-text { font-size: 0.75rem; font-family: monospace; color: var(--mute); }
    .modal-backdrop {
      position: fixed; inset: 0; background: var(--ink); opacity: 0.95; display: flex;
      align-items: center; justify-content: center; z-index: 1000;
    }
    .modal-card {
      background: var(--surf); border-radius: var(--fe-radius); max-width: 500px;
      width: 90%; padding: var(--fe-space-4); border: 1px solid var(--line);
      box-shadow: var(--shadow);
    }
    .modal-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; border-bottom: 1px solid var(--line); padding-bottom: 0.5rem; }
    .modal-header h3 { font-size: 1.1rem; font-weight: 700; color: var(--ink); margin: 0; }
    .close-btn { border: none; background: none; font-size: 1.5rem; cursor: pointer; color: var(--mute); }
    .form-group { margin-bottom: 1rem; }
    .form-group label { display: block; font-size: 0.875rem; font-weight: 600; margin-bottom: 0.25rem; color: var(--ink); }
    .fe-textarea, .fe-select, .fe-input {
      width: 100%; border: 1px solid var(--line); border-radius: var(--fe-radius);
      padding: 0.5rem; font-family: inherit; font-size: 0.875rem; background: var(--surf); color: var(--ink);
    }
    .modal-error { color: var(--bad); font-size: 0.8rem; margin-top: 0.5rem; font-weight: 600; }
    .modal-footer { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1.5rem; border-top: 1px solid var(--line); padding-top: 0.75rem; }
    .fe-btn { padding: 0.45rem 1rem; border-radius: var(--fe-radius); font-size: 0.875rem; font-weight: 600; cursor: pointer; border: 1px solid transparent; }
    .fe-btn--primary { background: var(--pri); color: var(--surf); }
    .fe-btn--secondary { background: var(--surf2); color: var(--ink); border-color: var(--line); }
    .fe-btn--danger { background: var(--bad); color: var(--surf); }
    .text-success { color: var(--ok); }
    .text-danger { color: var(--bad); }
    .text-warning { color: var(--warn); }
    .text-primary { color: var(--pri); }
    .text-empty { text-align: center; color: var(--mute); padding: 2rem !important; }
    .font-bold { font-weight: 700; }
  `],
})
export class ClinicReportsComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly projectContext = inject(ProjectContextService);

  readonly activeTab = signal<ReportTab>('productivity');
  readonly selectedWindow = signal<string>('30d');
  readonly reportDate = signal<string>('2026-10-20');
  readonly loading = signal<boolean>(false);
  readonly error = signal<boolean>(false);

  // Data signals
  readonly productivityRows = signal<AppointmentProductivityRow[]>([]);
  readonly procedureRows = signal<ProcedureUtilizationRow[]>([]);
  readonly financialRows = signal<FinancialCollectionSummaryRow[]>([]);
  readonly recallRows = signal<PatientRetentionRecallRow[]>([]);
  readonly complianceRows = signal<BreakGlassComplianceRow[]>([]);
  readonly auditRows = signal<ClinicAuditRow[]>([]);
  readonly activeGrants = signal<BreakGlassGrantRow[]>([]);
  readonly chainVerified = signal<boolean | null>(null);
  readonly exportResult = signal<ClinicAuditExportView | null>(null);

  // Modal signals
  readonly showBreakGlassModal = signal<boolean>(false);
  readonly showRevokeModal = signal<boolean>(false);
  readonly submitting = signal<boolean>(false);
  readonly breakGlassError = signal<string | null>(null);
  readonly revokeGrantId = signal<string | null>(null);

  breakGlassReason = '';
  breakGlassDuration = 4;
  revokeReason = '';

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });
  }

  ngOnInit(): void {
    this.refreshActiveGrants();
    this.loadCurrentTab();
  }

  hasData(): boolean {
    switch (this.activeTab()) {
      case 'productivity': return this.productivityRows().length > 0;
      case 'procedures': return this.procedureRows().length > 0;
      case 'financials': return this.financialRows().length > 0;
      case 'recall': return this.recallRows().length > 0;
      case 'compliance': return this.complianceRows().length > 0;
      case 'audit': return this.auditRows().length > 0;
    }
  }

  setTab(tab: ReportTab): void {
    this.activeTab.set(tab);
    this.loadCurrentTab();
  }

  setWindow(win: string): void {
    this.selectedWindow.set(win);
    this.loadCurrentTab();
  }

  onReportDateChange(value: string): void {
    this.reportDate.set(value);
    this.loadCurrentTab();
  }

  calculateDateRange(): { from?: string; to?: string } {
    const now = new Date();
    const to = now.toISOString();
    let fromDate = new Date();

    switch (this.selectedWindow()) {
      case '7d':
        fromDate.setDate(now.getDate() - 7);
        break;
      case '90d':
        fromDate.setDate(now.getDate() - 90);
        break;
      case 'year':
        fromDate.setFullYear(now.getFullYear() - 1);
        break;
      case '30d':
      default:
        fromDate.setDate(now.getDate() - 30);
        break;
    }
    return { from: fromDate.toISOString(), to };
  }

  async refreshActiveGrants(): Promise<void> {
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const grants = await this.api.activeBreakGlass({ projectId: proj });
      this.activeGrants.set(grants);
    } catch {
      // Non-blocking
    }
  }

  async loadCurrentTab(): Promise<void> {
    this.loading.set(true);
    this.error.set(false);
    const { from, to } = this.calculateDateRange();
    const proj = this.projectContext.selectedProjectId() || undefined;

    try {
      switch (this.activeTab()) {
        case 'productivity': {
          const rows = await this.api.appointmentProductivity(from, to, undefined, { projectId: proj });
          this.productivityRows.set(rows);
          break;
        }
        case 'procedures': {
          const rows = await this.api.procedureUtilization(from, to, { projectId: proj });
          this.procedureRows.set(rows);
          break;
        }
        case 'financials': {
          const rows = await this.api.financialCollection(from, to, { projectId: proj });
          this.financialRows.set(rows);
          break;
        }
        case 'recall': {
          const rows = await this.api.patientRecall(180, { projectId: proj });
          this.recallRows.set(rows);
          break;
        }
        case 'compliance': {
          const rows = await this.api.breakGlassCompliance(from, to, { projectId: proj });
          this.complianceRows.set(rows);
          break;
        }
        case 'audit': {
          const rows = await this.api.clinicAuditLogs({ from, to, projectId: proj });
          this.auditRows.set(rows);
          break;
        }
      }
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  async verifyChainIntegrity(): Promise<void> {
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const result = await this.api.verifyAuditChain({ projectId: proj });
      this.chainVerified.set(result.integrityVerified);
    } catch {
      this.chainVerified.set(false);
    }
  }

  async exportCurrentReport(): Promise<void> {
    const { from, to } = this.calculateDateRange();
    const proj = this.projectContext.selectedProjectId() || undefined;
    let exportType = 'APPOINTMENTS';
    switch (this.activeTab()) {
      case 'procedures': exportType = 'PROCEDURES'; break;
      case 'financials': exportType = 'FINANCIALS'; break;
      case 'recall': exportType = 'PATIENT_RECALL'; break;
      case 'compliance': exportType = 'BREAK_GLASS'; break;
      default: exportType = 'APPOINTMENTS'; break;
    }

    try {
      const exportView = await this.api.exportReport(exportType, from, to, { projectId: proj });
      this.exportResult.set(exportView);
    } catch {
      // Handle error
    }
  }

  dismissExport(): void {
    this.exportResult.set(null);
  }

  // --- Break-Glass Handlers ---

  openBreakGlassModal(): void {
    this.breakGlassReason = '';
    this.breakGlassDuration = 4;
    this.breakGlassError.set(null);
    this.showBreakGlassModal.set(true);
  }

  closeBreakGlassModal(): void {
    this.showBreakGlassModal.set(false);
  }

  async submitBreakGlass(): Promise<void> {
    if (!this.breakGlassReason || this.breakGlassReason.trim().length < 5) {
      this.breakGlassError.set('A detailed clinical justification (at least 5 characters) is required.');
      return;
    }

    this.submitting.set(true);
    this.breakGlassError.set(null);
    const proj = this.projectContext.selectedProjectId() || undefined;

    try {
      await this.api.requestBreakGlass(
        {
          reason: this.breakGlassReason.trim(),
          durationHours: this.breakGlassDuration,
        },
        { projectId: proj }
      );
      this.closeBreakGlassModal();
      await this.refreshActiveGrants();
      await this.loadCurrentTab();
    } catch (err: unknown) {
      this.breakGlassError.set('Failed to activate break-glass emergency access.');
    } finally {
      this.submitting.set(false);
    }
  }

  openRevokeModal(grantId: string): void {
    this.revokeGrantId.set(grantId);
    this.revokeReason = '';
    this.showRevokeModal.set(true);
  }

  closeRevokeModal(): void {
    this.showRevokeModal.set(false);
    this.revokeGrantId.set(null);
  }

  async submitRevoke(): Promise<void> {
    const grantId = this.revokeGrantId();
    if (!grantId) return;

    this.submitting.set(true);
    const proj = this.projectContext.selectedProjectId() || undefined;

    try {
      await this.api.revokeBreakGlass(
        grantId,
        { revocationReason: this.revokeReason.trim() || undefined },
        { projectId: proj }
      );
      this.closeRevokeModal();
      await this.refreshActiveGrants();
      await this.loadCurrentTab();
    } catch {
      // handle error
    } finally {
      this.submitting.set(false);
    }
  }

  async reviewGrant(grantId: string, status: 'APPROVED' | 'FLAGGED', notes: string): Promise<void> {
    const proj = this.projectContext.selectedProjectId() || undefined;
    try {
      await this.api.reviewBreakGlass(grantId, { reviewStatus: status, reviewNotes: notes }, { projectId: proj });
      await this.loadCurrentTab();
    } catch {
      // handle error
    }
  }
}
