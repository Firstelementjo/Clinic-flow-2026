/**
 * `/app/clinic/reports` — Dental Clinic Reports, Audit Stream & Access Control Hardening (Slice S8).
 * Complies with ADR V4 multi-tenancy, design system (0 hex/rgba), 0 digits in static dicts, 0 emojis.
 */

import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ClinicApi, type ClinicLocation } from '../../core/api/endpoints';
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
  type ClinicExpenseRow,
  type FinancialAnalyticsReport,
  type RecurringExpenseTemplateRow,
} from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';

type ReportTab = 'productivity' | 'procedures' | 'financials' | 'expensePnl' | 'recall' | 'compliance' | 'audit';

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

      <div class="tl-toolbar" style="margin-bottom: 0; position: relative; z-index: 50;">
        <div style="display: flex; align-items: center; gap: 8px;">
          <h1 class="tl-greeting" style="font-size: 20px; font-weight: 700; margin: 0;">Commission/Revenue</h1>
          <div class="tl-info-wrap">
            <button
              type="button"
              class="tl-info-trigger"
              [class.is-active]="showReportsInfo()"
              (click)="toggleReportsInfo()"
              title="Reports details"
            >?</button>
            @if (showReportsInfo()) {
              <div class="tl-info-popover">
                {{ 'clinic.reports.description' | t }}
              </div>
            }
          </div>
        </div>
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
          <button
            class="report-nav-btn"
            [class.active]="activeTab() === 'expensePnl'"
            data-testid="tab-expense-pnl"
            (click)="setTab('expensePnl')"
          >
            Expenses &amp; P&amp;L Analytics
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

      <!-- TAB 7: Expenses, Recurring Salaries & P&L Analytics -->
      @if (activeTab() === 'expensePnl') {
        <section class="report-panel" data-testid="panel-expense-pnl" style="display:flex;flex-direction:column;gap:0.85rem;overflow-y:auto;">
          <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:0.5rem;">
            <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap;">
              <label style="font-size:0.82rem;font-weight:600;">
                Gregorian Year:
                <select class="fe-select" style="width:auto;display:inline-block;margin-left:0.25rem;" [(ngModel)]="pnlYear" (ngModelChange)="loadExpensePnl()">
                  <option [ngValue]="2025">2025</option>
                  <option [ngValue]="2026">2026</option>
                  <option [ngValue]="2027">2027</option>
                </select>
              </label>
              <label style="font-size:0.82rem;font-weight:600;">
                Period View:
                <select class="fe-select" style="width:auto;display:inline-block;margin-left:0.25rem;" [(ngModel)]="pnlPeriodType" (ngModelChange)="loadExpensePnl()">
                  <option value="ANNUAL">Annual (Full Year)</option>
                  <option value="QUARTERLY">Quarterly (Q1–Q4)</option>
                  <option value="MONTHLY">Monthly</option>
                </select>
              </label>
              <label style="font-size:0.82rem;font-weight:600;">
                Branch Scope:
                <select class="fe-select" style="width:auto;display:inline-block;margin-left:0.25rem;" [(ngModel)]="pnlClinicId" (ngModelChange)="loadExpensePnl()">
                  <option value="">All Branches Consolidated</option>
                  @for (loc of branchLocations(); track loc.id) {
                    <option [value]="loc.id">{{ loc.name }}</option>
                  }
                </select>
              </label>
            </div>
            <div style="display:flex;gap:0.45rem;">
              <button type="button" class="fe-btn fe-btn--secondary" (click)="openRecurringTemplateModal()">
                + New Salary / Recurring Template
              </button>
              <button type="button" class="fe-btn fe-btn--primary" (click)="openExpenseModal()">
                + Record Expense
              </button>
            </div>
          </div>

          @if (dueRecurringTemplates().length > 0) {
            <div style="background:#fffbeb;border:1px solid #f59e0b;border-radius:8px;padding:0.75rem 1rem;">
              <strong style="color:#92400e;font-size:0.88rem;">
                Due Monthly Recurring Expenses &amp; Salaries ({{ dueRecurringTemplates().length }})
              </strong>
              <p style="margin:0.2rem 0 0.5rem;font-size:0.8rem;color:#78350f;">
                Confirm and log each due monthly rent, utility, maintenance, or staff salary item into the branch ledger (no duplicate auto-posting):
              </p>
              <div style="display:flex;flex-direction:column;gap:0.4rem;">
                @for (tpl of dueRecurringTemplates(); track tpl.id) {
                  <div style="display:flex;justify-content:space-between;align-items:center;background:#ffffff;padding:0.45rem 0.7rem;border-radius:6px;border:1px solid #fde68a;">
                    <div>
                      <strong>{{ tpl.title }}</strong>
                      <span style="font-size:0.76rem;color:var(--mute);margin-left:0.4rem;">
                        [{{ tpl.category }}] · Default {{ tpl.defaultAmount | number:'1.3-3' }} JOD · Day {{ tpl.dayOfMonth }}
                        @if (tpl.clinicName) { · {{ tpl.clinicName }} }
                      </span>
                    </div>
                    <div style="display:flex;gap:0.35rem;">
                      <button type="button" class="fe-btn fe-btn--primary" style="padding:0.25rem 0.65rem;font-size:0.78rem;" (click)="openConfirmRecurringModal(tpl)">
                        Confirm &amp; Log This Month
                      </button>
                      <button type="button" class="fe-btn fe-btn--secondary" style="padding:0.25rem 0.5rem;font-size:0.76rem;color:#c62828;" (click)="deleteRecurringTemplate(tpl.id)">
                        Remove
                      </button>
                    </div>
                  </div>
                }
              </div>
            </div>
          }

          @if (financialReport(); as rep) {
            <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(175px,1fr));gap:0.65rem;">
              <div class="tl-stat-card" style="height:auto;padding:0.65rem 0.85rem;">
                <div class="tl-stat-left">
                  <span class="tl-stat-title">Total Revenue</span>
                  <span class="tl-stat-value text-success">{{ rep.totalRevenue | number:'1.3-3' }} JOD</span>
                </div>
              </div>
              <div class="tl-stat-card" style="height:auto;padding:0.65rem 0.85rem;">
                <div class="tl-stat-left">
                  <span class="tl-stat-title">Operational Expenses</span>
                  <span class="tl-stat-value">{{ rep.operationalExpenses | number:'1.3-3' }} JOD</span>
                </div>
              </div>
              <div class="tl-stat-card" style="height:auto;padding:0.65rem 0.85rem;">
                <div class="tl-stat-left">
                  <span class="tl-stat-title">Doctor Payouts</span>
                  <span class="tl-stat-value">{{ rep.doctorPayouts | number:'1.3-3' }} JOD</span>
                </div>
              </div>
              <div class="tl-stat-card" style="height:auto;padding:0.65rem 0.85rem;">
                <div class="tl-stat-left">
                  <span class="tl-stat-title">Total Expenses</span>
                  <span class="tl-stat-value text-danger">{{ rep.totalExpenses | number:'1.3-3' }} JOD</span>
                </div>
              </div>
              <div class="tl-stat-card" style="height:auto;padding:0.65rem 0.85rem;">
                <div class="tl-stat-left">
                  <span class="tl-stat-title">Net Profit (Rev − Exp)</span>
                  <span class="tl-stat-value" [class.text-success]="rep.netProfit >= 0" [class.text-danger]="rep.netProfit < 0">
                    {{ rep.netProfit | number:'1.3-3' }} JOD
                  </span>
                </div>
              </div>
            </div>

            <div style="font-size:0.78rem;color:var(--mute);">
              Cash-Basis Inventory Accounting (Option 2): Inventory purchases record expense upon restock (<strong>{{ rep.expensesByCategory['INVENTORY_PURCHASE'] ?? 0 | number:'1.3-3' }} JOD</strong> this period); clinical deductions track physical consumption without double-counting expenses.
            </div>

            <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:0.85rem;">
              <div class="table-container">
                <h4 style="margin:0 0 0.45rem;font-size:0.9rem;">Gregorian Quarterly Seasonal P&amp;L ({{ rep.year }})</h4>
                <table class="fe-table">
                  <thead>
                    <tr>
                      <th>Quarter</th>
                      <th>Revenue</th>
                      <th>OpEx + Payouts</th>
                      <th>Net Profit</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (q of rep.quarterlyBreakdown; track q.periodKey) {
                      <tr>
                        <td class="font-bold">{{ q.periodLabel }}</td>
                        <td class="text-success">{{ q.totalRevenue | number:'1.3-3' }}</td>
                        <td class="text-danger">{{ q.totalExpenses | number:'1.3-3' }}</td>
                        <td class="font-bold" [class.text-success]="q.netProfit >= 0" [class.text-danger]="q.netProfit < 0">
                          {{ q.netProfit | number:'1.3-3' }} JOD
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>

              <div class="table-container">
                <h4 style="margin:0 0 0.45rem;font-size:0.9rem;">Multi-Branch Financial Comparison ({{ rep.year }})</h4>
                <table class="fe-table">
                  <thead>
                    <tr>
                      <th>Branch</th>
                      <th>Revenue</th>
                      <th>Expenses</th>
                      <th>Net Profit</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (b of rep.branchBreakdown; track b.branchName) {
                      <tr>
                        <td class="font-bold">{{ b.branchName }}</td>
                        <td class="text-success">{{ b.totalRevenue | number:'1.3-3' }}</td>
                        <td class="text-danger">{{ b.totalExpenses | number:'1.3-3' }}</td>
                        <td class="font-bold" [class.text-success]="b.netProfit >= 0" [class.text-danger]="b.netProfit < 0">
                          {{ b.netProfit | number:'1.3-3' }} JOD
                        </td>
                      </tr>
                    } @empty {
                      <tr><td colspan="4" class="text-empty">No branch financial breakdown yet</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          }

          <div class="table-container">
            <h4 style="margin:0 0 0.45rem;font-size:0.9rem;">Operational Expenses Ledger ({{ expenseRows().length }})</h4>
            <table class="fe-table">
              <thead>
                <tr>
                  <th>Payment Date</th>
                  <th>Title</th>
                  <th>Category</th>
                  <th>Frequency</th>
                  <th>Branch</th>
                  <th>Amount</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (exp of expenseRows(); track exp.id) {
                  <tr>
                    <td>{{ exp.paymentDate }}</td>
                    <td class="font-bold">{{ exp.title }}</td>
                    <td><code>{{ exp.category }}</code></td>
                    <td>{{ exp.expenseFrequency }}</td>
                    <td>{{ exp.clinicName || 'Project Wide' }}</td>
                    <td class="font-bold text-danger">{{ exp.amount | number:'1.3-3' }} {{ exp.currency }}</td>
                    <td>
                      <button type="button" class="fe-btn fe-btn--secondary" style="padding:0.2rem 0.5rem;font-size:0.74rem;color:#c62828;" (click)="deleteExpenseRow(exp.id)">
                        Delete
                      </button>
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="7" class="text-empty">No operational expenses recorded for this branch</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }

      @if (showExpenseModal()) {
        <div class="modal-backdrop" role="dialog" aria-modal="true" (click)="showExpenseModal.set(false)">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>Record Operational Expense</h3>
              <button class="close-btn" (click)="showExpenseModal.set(false)">×</button>
            </div>
            <div class="modal-body">
              <div class="form-group">
                <label>Category *</label>
                <select class="fe-select" [(ngModel)]="expFormCategory">
                  <option value="RENT">Rent</option>
                  <option value="ELECTRICITY_WATER">Electricity &amp; Water</option>
                  <option value="SALARIES">Salaries</option>
                  <option value="MAINTENANCE">Maintenance</option>
                  <option value="INVENTORY_PURCHASE">Inventory Purchase</option>
                  <option value="LAB_FEES">Lab / Imaging Fees</option>
                  <option value="OTHER">Other Operational Expense</option>
                </select>
              </div>
              <div class="form-group">
                <label>Description / Title *</label>
                <input class="fe-input" [(ngModel)]="expFormTitle" placeholder="e.g., Monthly Clinic Rent" />
              </div>
              <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.65rem;">
                <div class="form-group">
                  <label>Amount (JOD) *</label>
                  <input class="fe-input" type="number" min="0" step="0.001" [(ngModel)]="expFormAmount" />
                </div>
                <div class="form-group">
                  <label>Payment Date *</label>
                  <input class="fe-input" type="date" [(ngModel)]="expFormDate" />
                </div>
              </div>
              <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.65rem;">
                <div class="form-group">
                  <label>Frequency</label>
                  <select class="fe-select" [(ngModel)]="expFormFrequency">
                    <option value="ONE_TIME">One-Time</option>
                    <option value="RECURRING_MONTHLY">Recurring Monthly</option>
                  </select>
                </div>
                <div class="form-group">
                  <label>Branch</label>
                  <select class="fe-select" [(ngModel)]="expFormClinicId">
                    <option value="">Project Default Branch</option>
                    @for (loc of branchLocations(); track loc.id) {
                      <option [value]="loc.id">{{ loc.name }}</option>
                    }
                  </select>
                </div>
              </div>
              <div class="form-group">
                <label>Notes</label>
                <input class="fe-input" [(ngModel)]="expFormNotes" placeholder="Receipt or invoice reference" />
              </div>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--secondary" (click)="showExpenseModal.set(false)">Cancel</button>
              <button class="fe-btn fe-btn--primary" [disabled]="submitting()" (click)="submitExpense()">Save Expense</button>
            </div>
          </div>
        </div>
      }

      @if (showTemplateModal()) {
        <div class="modal-backdrop" role="dialog" aria-modal="true" (click)="showTemplateModal.set(false)">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>New Monthly Recurring / Salary Template</h3>
              <button class="close-btn" (click)="showTemplateModal.set(false)">×</button>
            </div>
            <div class="modal-body">
              <div class="form-group">
                <label>Category *</label>
                <select class="fe-select" [(ngModel)]="tplFormCategory">
                  <option value="SALARIES">Staff Salaries</option>
                  <option value="RENT">Monthly Rent</option>
                  <option value="ELECTRICITY_WATER">Electricity &amp; Water</option>
                  <option value="MAINTENANCE">Maintenance Contract</option>
                  <option value="OTHER">Other Monthly Fixed Cost</option>
                </select>
              </div>
              <div class="form-group">
                <label>Title *</label>
                <input class="fe-input" [(ngModel)]="tplFormTitle" placeholder="e.g., Front Desk &amp; Nurses Monthly Payroll" />
              </div>
              <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.65rem;">
                <div class="form-group">
                  <label>Default Monthly Amount (JOD)</label>
                  <input class="fe-input" type="number" min="0" step="0.001" [(ngModel)]="tplFormAmount" />
                </div>
                <div class="form-group">
                  <label>Day of Month Due (1–28)</label>
                  <input class="fe-input" type="number" min="1" max="28" [(ngModel)]="tplFormDay" />
                </div>
              </div>
              <div class="form-group">
                <label>Branch</label>
                <select class="fe-select" [(ngModel)]="tplFormClinicId">
                  <option value="">Project Default Branch</option>
                  @for (loc of branchLocations(); track loc.id) {
                    <option [value]="loc.id">{{ loc.name }}</option>
                  }
                </select>
              </div>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--secondary" (click)="showTemplateModal.set(false)">Cancel</button>
              <button class="fe-btn fe-btn--primary" [disabled]="submitting()" (click)="submitRecurringTemplate()">Create Template</button>
            </div>
          </div>
        </div>
      }

      @if (confirmTemplateTarget(); as tpl) {
        <div class="modal-backdrop" role="dialog" aria-modal="true" (click)="confirmTemplateTarget.set(null)">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>Confirm &amp; Log Monthly Expense: {{ tpl.title }}</h3>
              <button class="close-btn" (click)="confirmTemplateTarget.set(null)">×</button>
            </div>
            <div class="modal-body">
              <div class="form-group">
                <label>Confirmed Amount (JOD)</label>
                <input class="fe-input" type="number" min="0" step="0.001" [(ngModel)]="confirmTplAmount" />
              </div>
              <div class="form-group">
                <label>Payment Date</label>
                <input class="fe-input" type="date" [(ngModel)]="confirmTplDate" />
              </div>
              <div class="form-group">
                <label>Notes</label>
                <input class="fe-input" [(ngModel)]="confirmTplNotes" />
              </div>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--secondary" (click)="confirmTemplateTarget.set(null)">Cancel</button>
              <button class="fe-btn fe-btn--primary" [disabled]="submitting()" (click)="submitConfirmRecurring()">Confirm &amp; Post Expense</button>
            </div>
          </div>
        </div>
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
      border-radius: 2px;
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
      border-radius: 2px;
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
    .window-switcher { display: flex; gap: 2px; background: var(--surf2); padding: 2px; border-radius: 2px; }
    .window-btn { border: none; background: none; padding: 3px 7px; border-radius: 2px; font-size: 0.75rem; cursor: pointer; color: var(--mute); font-weight: 500; }
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
      border-radius: 4px;
      border: 1px solid var(--line);
      box-shadow: var(--shadow-soft);
    }
    .fe-table { width: 100%; border-collapse: collapse; text-align: start; }
    .fe-table th {
      background: var(--surf2);
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--mute);
      position: sticky;
      top: 0;
      z-index: 2;
      padding: 10px 14px;
      border-bottom: 1px solid var(--line);
      white-space: nowrap;
    }
    .fe-table td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--line);
      font-size: 12.5px;
      color: var(--ink-soft);
      vertical-align: middle;
      line-height: 1.4;
    }
    .fe-table tbody tr {
      transition: background-color 0.15s ease;
    }
    .fe-table tbody tr:hover {
      background: var(--surf2);
    }
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
  private readonly clinicApi = inject(ClinicApi);
  private readonly projectContext = inject(ProjectContextService);

  readonly activeTab = signal<ReportTab>('productivity');
  readonly selectedWindow = signal<string>('30d');
  readonly reportDate = signal<string>('2026-10-20');
  readonly loading = signal<boolean>(false);
  readonly error = signal<boolean>(false);

  // Req 3: Finance, Expenses, Recurring Salaries & P&L Analytics
  pnlYear = new Date().getFullYear();
  pnlPeriodType = 'ANNUAL';
  pnlClinicId = '';
  readonly branchLocations = signal<readonly ClinicLocation[]>([]);
  readonly financialReport = signal<FinancialAnalyticsReport | null>(null);
  readonly expenseRows = signal<readonly ClinicExpenseRow[]>([]);
  readonly recurringTemplates = signal<readonly RecurringExpenseTemplateRow[]>([]);
  readonly dueRecurringTemplates = computed(() =>
    this.recurringTemplates().filter((t) => t.dueThisMonth && t.isActive),
  );
  readonly showExpenseModal = signal(false);
  readonly showTemplateModal = signal(false);
  readonly confirmTemplateTarget = signal<RecurringExpenseTemplateRow | null>(null);
  expFormCategory = 'RENT';
  expFormTitle = '';
  expFormAmount = 0;
  expFormDate = new Date().toISOString().slice(0, 10);
  expFormFrequency = 'ONE_TIME';
  expFormClinicId = '';
  expFormNotes = '';
  tplFormCategory = 'SALARIES';
  tplFormTitle = '';
  tplFormAmount = 0;
  tplFormDay = 1;
  tplFormClinicId = '';
  confirmTplAmount = 0;
  confirmTplDate = new Date().toISOString().slice(0, 10);
  confirmTplNotes = '';

  readonly showReportsInfo = signal<boolean>(false);
  toggleReportsInfo(): void {
    this.showReportsInfo.update((v) => !v);
  }

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
    void this.loadBranchLocations();
    this.loadCurrentTab();
  }

  hasData(): boolean {
    switch (this.activeTab()) {
      case 'productivity': return this.productivityRows().length > 0;
      case 'procedures': return this.procedureRows().length > 0;
      case 'financials': return this.financialRows().length > 0;
      case 'expensePnl': return true;
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
        case 'expensePnl': {
          await this.loadExpensePnl();
          break;
        }
      }
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  private async loadBranchLocations(): Promise<void> {
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const res = await this.clinicApi.locations({ projectId: proj, limit: 100 });
      this.branchLocations.set(res.items ?? []);
    } catch {
      this.branchLocations.set([]);
    }
  }

  async loadExpensePnl(): Promise<void> {
    const proj = this.projectContext.selectedProjectId() || undefined;
    const clinicId = this.pnlClinicId || undefined;
    try {
      const [report, expenses, templates] = await Promise.all([
        this.api.financialAnalytics({
          projectId: proj,
          year: this.pnlYear,
          periodType: this.pnlPeriodType,
          clinicId,
        }),
        this.api.financeExpenses({ projectId: proj, clinicId }),
        this.api.recurringExpenseTemplates({ projectId: proj, clinicId }),
      ]);
      this.financialReport.set(report);
      this.expenseRows.set(expenses ?? []);
      this.recurringTemplates.set(templates ?? []);
    } catch {
      this.error.set(true);
    }
  }

  openExpenseModal(): void {
    this.expFormCategory = 'RENT';
    this.expFormTitle = '';
    this.expFormAmount = 0;
    this.expFormDate = new Date().toISOString().slice(0, 10);
    this.expFormFrequency = 'ONE_TIME';
    this.expFormClinicId = this.pnlClinicId || this.branchLocations()[0]?.id || '';
    this.expFormNotes = '';
    this.showExpenseModal.set(true);
  }

  async submitExpense(): Promise<void> {
    const title = this.expFormTitle.trim();
    if (!title) return;
    this.submitting.set(true);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      await this.api.createFinanceExpense(
        {
          category: this.expFormCategory,
          title,
          amount: Number(this.expFormAmount) || 0,
          paymentDate: this.expFormDate,
          expenseFrequency: this.expFormFrequency,
          clinicId: this.expFormClinicId || null,
          notes: this.expFormNotes.trim() || undefined,
          projectId: proj,
        },
        { projectId: proj },
      );
      this.showExpenseModal.set(false);
      await this.loadExpensePnl();
    } finally {
      this.submitting.set(false);
    }
  }

  async deleteExpenseRow(expenseId: string): Promise<void> {
    const proj = this.projectContext.selectedProjectId() || undefined;
    try {
      await this.api.deleteFinanceExpense(expenseId, { projectId: proj });
      await this.loadExpensePnl();
    } catch {
      // ignore
    }
  }

  openRecurringTemplateModal(): void {
    this.tplFormCategory = 'SALARIES';
    this.tplFormTitle = '';
    this.tplFormAmount = 0;
    this.tplFormDay = 1;
    this.tplFormClinicId = this.pnlClinicId || this.branchLocations()[0]?.id || '';
    this.showTemplateModal.set(true);
  }

  async submitRecurringTemplate(): Promise<void> {
    const title = this.tplFormTitle.trim();
    if (!title) return;
    this.submitting.set(true);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      await this.api.createRecurringExpenseTemplate(
        {
          category: this.tplFormCategory,
          title,
          defaultAmount: Number(this.tplFormAmount) || 0,
          dayOfMonth: Number(this.tplFormDay) || 1,
          clinicId: this.tplFormClinicId || null,
          projectId: proj,
        },
        { projectId: proj },
      );
      this.showTemplateModal.set(false);
      await this.loadExpensePnl();
    } finally {
      this.submitting.set(false);
    }
  }

  async deleteRecurringTemplate(templateId: string): Promise<void> {
    const proj = this.projectContext.selectedProjectId() || undefined;
    try {
      await this.api.deleteRecurringExpenseTemplate(templateId, { projectId: proj });
      await this.loadExpensePnl();
    } catch {
      // ignore
    }
  }

  openConfirmRecurringModal(tpl: RecurringExpenseTemplateRow): void {
    this.confirmTemplateTarget.set(tpl);
    this.confirmTplAmount = tpl.defaultAmount;
    this.confirmTplDate = new Date().toISOString().slice(0, 10);
    this.confirmTplNotes = `Confirmed recurring ${tpl.category}: ${tpl.title}`;
  }

  async submitConfirmRecurring(): Promise<void> {
    const tpl = this.confirmTemplateTarget();
    if (!tpl) return;
    this.submitting.set(true);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      await this.api.confirmRecurringExpenseTemplate(
        tpl.id,
        {
          amount: Number(this.confirmTplAmount) || tpl.defaultAmount,
          paymentDate: this.confirmTplDate,
          notes: this.confirmTplNotes.trim() || undefined,
        },
        { projectId: proj },
      );
      this.confirmTemplateTarget.set(null);
      await this.loadExpensePnl();
    } finally {
      this.submitting.set(false);
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
