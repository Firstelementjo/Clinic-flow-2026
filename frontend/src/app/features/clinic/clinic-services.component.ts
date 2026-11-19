/**
 * `/app/clinic/services-and-resources` — the Service catalogue.
 *
 * The old screen rendered the read-only procedures endpoint and an honest card
 * saying shared resources had no route. This rewrite keeps the read and adds the
 * write the ToothLogic Create Service drawer needs: name, category, product code,
 * clinic, price, duration, slot, type, discount and image.
 *
 * The catalogue is small enough that search and status are filtered in the browser
 * after one read; the server list is capped at the page it returns, so the footer
 * says how many rows this view holds rather than implying it holds the world.
 */
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/api/api.service';
import {
  ClinicApi,
  type ClinicLocation,
  type ClinicProcedure,
  type ClinicProcedureInput,
} from '../../core/api/endpoints';
import {
  ClinicPatientApi,
  type AdjustInventoryStockPayload,
  type ConsolidatedInventoryRow,
  type CreateInventoryItemPayload,
  type InventoryItemRow,
  type InventoryTransactionRow,
  type ServiceMaterialInput,
} from './clinic-patient.api';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionService } from '../../core/auth/session.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TlDrawerComponent } from '../../ui/tl-drawer.component';
import { clinicAdminStrings } from './clinic-admin.strings';

type StatusFilter = '' | 'ACTIVE' | 'INACTIVE';
type BulkAction = '' | 'ACTIVE' | 'INACTIVE';
type Toast = { readonly message: string; readonly error: boolean };
type ServicePageTab = 'services' | 'inventory' | 'consolidated' | 'transactions';

const SERVICE_TYPES = ['IN_CLINIC', 'ONLINE', 'HOME_VISIT'] as const;
const SYSTEM_SERVICES = ['GENERAL', 'SCALING', 'ORTHODONTICS', 'IMPLANTS', 'ROOT_CANAL'] as const;
const INVENTORY_CATEGORIES = ['CONSUMABLE', 'ANESTHETIC', 'FILLING', 'IMPLANT', 'ORTHO', 'OTHER'] as const;

@Component({
  selector: 'fe-clinic-services-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, ReactiveFormsModule, RouterLink, TlDrawerComponent, TranslatePipe],
  template: `
    <div class="tl-page" data-testid="clinic-services">
      <div class="tl-page-top">
        <nav class="tl-breadcrumbs" aria-label="Breadcrumb">
          <a routerLink="/app/dashboard">{{ 'clinicAdmin.nav.home' | t }}</a>
          <span aria-hidden="true">&gt;&gt;</span>
          <span class="active">{{ 'clinicAdmin.services.breadcrumb' | t }}</span>
        </nav>
        <a
          class="tl-btn tl-btn-dark"
          routerLink="/app/clinic/appointments"
          [queryParams]="{ create: '1' }"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
            <circle cx="12" cy="12" r="9" />
            <path d="M12 8v8M8 12h8" />
          </svg>
          {{ 'clinicAdmin.common.appointment' | t }}
        </a>
      </div>

      <nav class="tl-tabs-nav" aria-label="Services and Inventory Sections">
        <button
          type="button"
          class="tl-tab-btn"
          [class.tl-tab-btn--active]="activeTab() === 'services'"
          (click)="switchTab('services')"
        >
          Services Catalogue ({{ rows().length }})
        </button>
        <button
          type="button"
          class="tl-tab-btn"
          [class.tl-tab-btn--active]="activeTab() === 'inventory'"
          (click)="switchTab('inventory')"
        >
          Branch Inventory ({{ inventoryItems().length }})
        </button>
        <button
          type="button"
          class="tl-tab-btn"
          [class.tl-tab-btn--active]="activeTab() === 'consolidated'"
          (click)="switchTab('consolidated')"
        >
          Multi-Branch Consolidated
        </button>
        <button
          type="button"
          class="tl-tab-btn"
          [class.tl-tab-btn--active]="activeTab() === 'transactions'"
          (click)="switchTab('transactions')"
        >
          Inventory Audit Log
        </button>
      </nav>

      @if (activeTab() === 'services') {
      <div class="tl-toolbar">
        <div class="tl-toolbar-left">
          <select
            class="tl-select"
            [value]="bulkAction()"
            (change)="onBulkAction($any($event.target).value)"
            [attr.aria-label]="'clinicAdmin.common.bulkAction' | t"
          >
            <option value="">{{ 'clinicAdmin.common.noAction' | t }}</option>
            <option value="ACTIVE">{{ 'clinicAdmin.common.active' | t }}</option>
            <option value="INACTIVE">{{ 'clinicAdmin.common.inactive' | t }}</option>
          </select>
          <button
            type="button"
            class="tl-btn tl-btn-gray"
            (click)="applyBulk()"
            [disabled]="!canManage() || bulkAction() === '' || selectedIds().size === 0"
          >
            {{ 'clinicAdmin.common.apply' | t }}
          </button>
          <button
            type="button"
            class="tl-btn tl-btn-teal"
            (click)="exportCsv()"
            [disabled]="filteredRows().length === 0"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
            </svg>
            {{ 'clinicAdmin.common.export' | t }}
          </button>
        </div>

        <div class="tl-toolbar-right">
          <select
            class="tl-select"
            [value]="statusFilter()"
            (change)="onStatusChange($any($event.target).value)"
            [attr.aria-label]="'clinicAdmin.common.status' | t"
          >
            <option value="">{{ 'clinicAdmin.common.all' | t }}</option>
            <option value="ACTIVE">{{ 'clinicAdmin.common.activeOnly' | t }}</option>
            <option value="INACTIVE">{{ 'clinicAdmin.common.inactiveOnly' | t }}</option>
          </select>

          <div class="tl-search-box">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" />
            </svg>
            <input
              type="search"
              [value]="searchTerm()"
              (input)="onSearch($any($event.target).value)"
              [attr.placeholder]="'clinicAdmin.services.searchPlaceholder' | t"
              [attr.aria-label]="'clinicAdmin.common.search' | t"
            />
          </div>

          <button
            type="button"
            class="tl-btn tl-btn-teal"
            (click)="openCreate()"
            [disabled]="!canManage()"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true">
              <path d="M12 5v14M5 12h14" />
            </svg>
            {{ 'clinicAdmin.common.new' | t }}
          </button>
        </div>
      </div>

      <div class="tl-table-wrap" data-testid="clinic-procedures">
        @if (loading() && rows().length === 0) {
          <div class="tl-table-empty" role="status">{{ 'clinicAdmin.common.loading' | t }}</div>
        } @else if (loadFailed()) {
          <div class="tl-table-empty" role="alert">
            <strong>{{ 'clinicAdmin.common.loadFailed' | t }}</strong>
            <button type="button" class="tl-btn tl-btn-gray" (click)="load()">
              {{ 'clinicAdmin.common.retry' | t }}
            </button>
          </div>
        } @else if (filteredRows().length === 0) {
          <div class="tl-table-empty" data-testid="clinic-services-empty">
            <strong>{{ 'clinicAdmin.services.emptyTitle' | t }}</strong>
            <span>{{ 'clinicAdmin.services.emptyBody' | t }}</span>
          </div>
        } @else {
          <table class="tl-table">
            <caption class="fe-sr">{{ 'clinicAdmin.services.title' | t }}</caption>
            <thead>
              <tr>
                <th scope="col" style="width: 44px">
                  <input
                    type="checkbox"
                    class="tl-table-check"
                    [checked]="allPageSelected()"
                    (change)="toggleAll($any($event.target).checked)"
                    [attr.aria-label]="'clinicAdmin.common.selectAll' | t"
                  />
                </th>
                <th scope="col">{{ 'clinicAdmin.services.name' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.services.productCode' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.services.price' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.services.duration' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.services.category' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.services.clinic' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.common.status' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.common.actions' | t }}</th>
              </tr>
            </thead>
            <tbody>
              @for (row of pagedRows(); track row.id ?? $index) {
                <tr>
                  <td>
                    <input
                      type="checkbox"
                      class="tl-table-check"
                      [checked]="isSelected(row.id)"
                      (change)="toggleSelection(row.id, $any($event.target).checked)"
                      [attr.aria-label]="'clinicAdmin.common.select' | t"
                    />
                  </td>
                  <td>
                    <div class="tl-cell-profile">
                      <div class="tl-avatar">
                        @if (row.imageUrl) {
                          <img [src]="row.imageUrl" [alt]="row.name ?? ''" />
                        } @else {
                          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                            <path d="M12 4.5c-1.8-1.5-4.5-1.5-6.5 0-2 1.5-2.5 4.5-1.5 7 .5 1.5 1 3 1.5 5 .5 2 1.5 3.5 2.5 3.5 1 0 1.5-1.5 2-3.5.3-1.2.7-2 2-2s1.7.8 2 2c.5 2 1 3.5 2 3.5 1 0 2-1.5 2.5-3.5.5-2 1-3.5 1.5-5 1-2.5.5-5.5-1.5-7-2-1.5-4.7-1.5-6.5 0z" />
                          </svg>
                        }
                      </div>
                      <div class="tl-profile-info">
                        <span class="tl-profile-name">{{ row.name || '—' }}</span>
                        <span class="tl-profile-sub">
                          {{ row.subCategory || row.systemService || ('clinicAdmin.services.general' | t) }}
                        </span>
                      </div>
                    </div>
                  </td>
                  <td>{{ row.productCode || '—' }}</td>
                  <td>
                    @if (row.price === undefined || row.price === null) {
                      <span class="tl-muted" data-testid="clinic-procedure-unpriced">
                        {{ 'clinicAdmin.services.notPriced' | t }}
                      </span>
                    } @else {
                      <span class="tl-money">{{ format.currency(row.price) }}</span>
                    }
                  </td>
                  <td>
                    {{ format.integer(row.durationMinutes) }} {{ 'clinicAdmin.services.minutes' | t }}
                  </td>
                  <td>{{ row.specialty || '—' }}</td>
                  <td>{{ row.clinicName || ('clinicAdmin.services.unassignedClinic' | t) }}</td>
                  <td>
                    <label class="tl-switch">
                      <input
                        type="checkbox"
                        [checked]="row.isActive === true"
                        [disabled]="!canManage()"
                        (change)="toggleActive(row, $any($event.target).checked, $any($event.target))"
                        [attr.aria-label]="'clinicAdmin.common.status' | t"
                      />
                      <span class="tl-slider"></span>
                    </label>
                  </td>
                  <td>
                    <div class="tl-actions">
                      <button
                        type="button"
                        class="tl-action-icon edit"
                        (click)="openEdit(row)"
                        [disabled]="!canManage()"
                        [attr.aria-label]="'clinicAdmin.common.edit' | t"
                      >
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                          <path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
                        </svg>
                      </button>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>

          <div class="tl-table-footer">
            <span>
              {{ 'clinicAdmin.common.showing' | t }} {{ showingFrom() }}–{{ showingTo() }}
              {{ 'clinicAdmin.common.of' | t }} {{ filteredRows().length }} {{ 'clinicAdmin.common.entries' | t }}
            </span>
            <div class="tl-pagination">
              <button
                type="button"
                class="tl-page-btn"
                (click)="goToPage(page() - 1)"
                [disabled]="page() === 1"
                [attr.aria-label]="'clinicAdmin.common.previous' | t"
              >
                ‹
              </button>
              @for (number of pageNumbers(); track number) {
                <button
                  type="button"
                  class="tl-page-btn"
                  [class.active]="number === page()"
                  (click)="goToPage(number)"
                >
                  {{ number }}
                </button>
              }
              <button
                type="button"
                class="tl-page-btn"
                (click)="goToPage(page() + 1)"
                [disabled]="page() === pageCount()"
                [attr.aria-label]="'clinicAdmin.common.next' | t"
              >
                ›
              </button>
            </div>
          </div>
        }
      </div>
      }

      @if (activeTab() === 'inventory') {
        <div class="tl-toolbar">
          <div class="tl-toolbar-left">
            <select
              class="tl-select"
              [ngModel]="inventoryClinicFilter()"
              (ngModelChange)="inventoryClinicFilter.set($event); loadInventory()"
              aria-label="Filter by Branch"
            >
              <option value="">All Branches in Scope</option>
              @for (loc of locations(); track loc.id) {
                <option [value]="loc.id">{{ loc.name }}</option>
              }
            </select>
            <label style="display:inline-flex;align-items:center;gap:0.4rem;font-size:0.84rem;font-weight:600;color:var(--ink);">
              <input
                type="checkbox"
                [ngModel]="inventoryLowStockOnly()"
                (ngModelChange)="inventoryLowStockOnly.set($event); loadInventory()"
              />
              Low Stock Only
            </label>
          </div>

          <div class="tl-toolbar-right">
            <div class="tl-search-box">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <circle cx="11" cy="11" r="7" />
                <path d="m20 20-3.5-3.5" />
              </svg>
              <input
                type="search"
                [ngModel]="inventorySearch()"
                (ngModelChange)="inventorySearch.set($event); loadInventory()"
                placeholder="Search material name or SKU..."
                aria-label="Search Inventory"
              />
            </div>

            <button
              type="button"
              class="tl-btn tl-btn-teal"
              (click)="openCreateInventory()"
              [disabled]="!canManage()"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
              New Material
            </button>
          </div>
        </div>

        <div class="tl-table-wrap">
          @if (inventoryLoading()) {
            <div class="tl-table-empty" role="status">Loading branch inventory...</div>
          } @else if (inventoryItems().length === 0) {
            <div class="tl-table-empty">
              <strong>No inventory materials recorded yet</strong>
              <span>Add consumables, anesthetics, filling materials, or implants to link them with dental services.</span>
            </div>
          } @else {
            <table class="tl-table">
              <thead>
                <tr>
                  <th>Material</th>
                  <th>SKU / Category</th>
                  <th>Branch</th>
                  <th>On Hand</th>
                  <th>Min Threshold</th>
                  <th>Unit Cost</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (item of inventoryItems(); track item.id) {
                  <tr>
                    <td><strong>{{ item.name }}</strong></td>
                    <td>
                      <div>{{ item.sku || '—' }}</div>
                      <small style="color:var(--mute);">{{ item.category }}</small>
                    </td>
                    <td>{{ item.clinicName || 'Project Wide' }}</td>
                    <td>
                      <strong>{{ item.quantityOnHand }}</strong> {{ item.unitOfMeasure }}
                    </td>
                    <td>{{ item.minStockThreshold }} {{ item.unitOfMeasure }}</td>
                    <td>{{ format.currency(item.unitCost) }}</td>
                    <td>
                      @if (item.lowStock) {
                        <span class="tl-pill tl-pill--warn">LOW STOCK</span>
                      } @else {
                        <span class="tl-pill tl-pill--ok">OK</span>
                      }
                    </td>
                    <td>
                      <div style="display:flex;gap:0.35rem;flex-wrap:wrap;">
                        <button
                          type="button"
                          class="tl-btn tl-btn-teal"
                          style="padding:0.25rem 0.55rem;font-size:0.76rem;"
                          (click)="openAdjustStock(item)"
                          [disabled]="!canManage()"
                        >
                          Restock / Adjust
                        </button>
                        <button
                          type="button"
                          class="tl-btn tl-btn-gray"
                          style="padding:0.25rem 0.55rem;font-size:0.76rem;"
                          (click)="openEditInventory(item)"
                          [disabled]="!canManage()"
                        >
                          Edit
                        </button>
                        <button
                          type="button"
                          class="tl-btn tl-btn-gray"
                          style="padding:0.25rem 0.55rem;font-size:0.76rem;color:#c62828;"
                          (click)="deleteInventory(item)"
                          [disabled]="!canManage()"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
      }

      @if (activeTab() === 'consolidated') {
        <div class="tl-toolbar">
          <div class="tl-toolbar-left">
            <strong style="font-size:0.9rem;color:var(--ink);">Consolidated Multi-Branch Inventory Summary</strong>
          </div>
          <div class="tl-toolbar-right">
            <div class="tl-search-box">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <circle cx="11" cy="11" r="7" />
                <path d="m20 20-3.5-3.5" />
              </svg>
              <input
                type="search"
                [ngModel]="consolidatedSearch()"
                (ngModelChange)="consolidatedSearch.set($event); loadConsolidated()"
                placeholder="Search consolidated materials..."
                aria-label="Search Consolidated"
              />
            </div>
            <button type="button" class="tl-btn tl-btn-gray" (click)="loadConsolidated()">Refresh</button>
          </div>
        </div>

        <div class="tl-table-wrap">
          @if (consolidatedLoading()) {
            <div class="tl-table-empty">Loading consolidated inventory...</div>
          } @else if (consolidatedItems().length === 0) {
            <div class="tl-table-empty"><strong>No consolidated inventory items found</strong></div>
          } @else {
            <table class="tl-table">
              <thead>
                <tr>
                  <th>Material</th>
                  <th>SKU</th>
                  <th>Category</th>
                  <th>Total Qty On Hand</th>
                  <th>Branches</th>
                  <th>Low-Stock Branches</th>
                  <th>Total Valuation</th>
                </tr>
              </thead>
              <tbody>
                @for (row of consolidatedItems(); track row.itemName + (row.sku || '')) {
                  <tr>
                    <td><strong>{{ row.itemName }}</strong></td>
                    <td>{{ row.sku || '—' }}</td>
                    <td>{{ row.category }}</td>
                    <td><strong>{{ row.totalQuantityOnHand }}</strong> {{ row.unitOfMeasure }}</td>
                    <td>{{ row.branchesCount }}</td>
                    <td>
                      @if (row.lowStockBranchesCount > 0) {
                        <span class="tl-pill tl-pill--warn">{{ row.lowStockBranchesCount }} Low</span>
                      } @else {
                        <span class="tl-pill tl-pill--ok">0 Low</span>
                      }
                    </td>
                    <td><strong>{{ format.currency(row.totalValuation) }}</strong></td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
      }

      @if (activeTab() === 'transactions') {
        <div class="tl-toolbar">
          <div class="tl-toolbar-left">
            <select
              class="tl-select"
              [ngModel]="transactionItemFilter()"
              (ngModelChange)="transactionItemFilter.set($event); loadTransactions()"
              aria-label="Filter by Material"
            >
              <option value="">All Materials</option>
              @for (item of inventoryItems(); track item.id) {
                <option [value]="item.id">{{ item.name }}</option>
              }
            </select>
          </div>
          <div class="tl-toolbar-right">
            <button type="button" class="tl-btn tl-btn-gray" (click)="loadTransactions()">Refresh Log</button>
          </div>
        </div>

        <div class="tl-table-wrap">
          @if (transactionsLoading()) {
            <div class="tl-table-empty">Loading inventory transactions...</div>
          } @else if (inventoryTxList().length === 0) {
            <div class="tl-table-empty"><strong>No inventory transactions recorded yet</strong></div>
          } @else {
            <table class="tl-table">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Material</th>
                  <th>Branch</th>
                  <th>Type</th>
                  <th>Qty Change</th>
                  <th>Before → After</th>
                  <th>Shortfall</th>
                  <th>Unit Cost</th>
                  <th>Reference / Notes</th>
                </tr>
              </thead>
              <tbody>
                @for (tx of inventoryTxList(); track tx.id) {
                  <tr>
                    <td>{{ format.dateTime(tx.createdAt) }}</td>
                    <td><strong>{{ tx.itemName }}</strong></td>
                    <td>{{ tx.clinicName || '—' }}</td>
                    <td><span class="tl-pill tl-pill--info">{{ tx.transactionType }}</span></td>
                    <td [style.color]="tx.quantity < 0 ? '#c62828' : '#00796b'" style="font-weight:700;">
                      {{ tx.quantity > 0 ? '+' + tx.quantity : tx.quantity }}
                    </td>
                    <td>{{ tx.quantityBefore }} → {{ tx.quantityAfter }}</td>
                    <td>
                      @if (tx.shortfallQuantity > 0) {
                        <span class="tl-pill tl-pill--warn">{{ tx.shortfallQuantity }}</span>
                      } @else {
                        0
                      }
                    </td>
                    <td>{{ format.currency(tx.unitCost) }}</td>
                    <td>
                      <small style="color:var(--mute);">{{ tx.referenceType || '' }} {{ tx.notes || '' }}</small>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
      }
    </div>

    <fe-tl-drawer
      [open]="drawerOpen()"
      [title]="(editing() ? 'clinicAdmin.services.editTitle' : 'clinicAdmin.services.createTitle') | t"
      [subtitle]="'clinicAdmin.services.drawerHint' | t"
      (closed)="closeDrawer()"
    >
      <form id="clinic-service-form" class="tl-form-grid" [formGroup]="form" (ngSubmit)="save()">
        @if (saveFailed()) {
          <div class="tl-form-error" role="alert">
            {{ saveErrorMessage() || ('clinicAdmin.common.saveFailed' | t) }}
            @if (saveRequestId(); as requestId) {
              <span class="tl-muted"> ({{ requestId }})</span>
            }
          </div>
        }

        <div class="tl-drawer-top-split">
          <div class="tl-field">
            <span class="tl-label">{{ 'clinicAdmin.services.image' | t }}</span>
            <label
              class="tl-file-drop"
              (dragover)="$event.preventDefault()"
              (drop)="onDrop($event)"
            >
              @if (form.controls.imageUrl.value) {
                <img class="tl-file-preview" [src]="form.controls.imageUrl.value" alt="" />
              } @else {
                <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
                  <rect x="3" y="4" width="18" height="16" rx="2" />
                  <circle cx="8.5" cy="9.5" r="1.5" />
                  <path d="m21 15-5-5L5 20" />
                </svg>
                <span><strong>{{ 'clinicAdmin.common.browse' | t }}</strong> {{ 'clinicAdmin.common.orDrop' | t }}</span>
                <span class="tl-hint">{{ 'clinicAdmin.services.imageHint' | t }}</span>
              }
              <input type="file" accept="image/*" (change)="onFile($event)" />
            </label>
            @if (form.controls.imageUrl.value) {
              <button type="button" class="tl-btn tl-btn-gray" (click)="removeImage()">
                {{ 'clinicAdmin.common.removeImage' | t }}
              </button>
            }
          </div>

          <div class="tl-drawer-stack">
            <div class="tl-field">
              <label class="tl-label" for="service-system">{{ 'clinicAdmin.services.systemService' | t }}</label>
              <select id="service-system" class="tl-select--field" formControlName="systemService">
                <option value="">{{ 'clinicAdmin.services.notSelected' | t }}</option>
                @for (option of systemServices; track option) {
                  <option [value]="option">{{ ('clinicAdmin.services.system.' + option) | t }}</option>
                }
              </select>
            </div>

            <div class="tl-field">
              <label class="tl-label" for="service-name">
                {{ 'clinicAdmin.services.name' | t }} <span class="required">*</span>
              </label>
              <input
                id="service-name"
                class="tl-input"
                formControlName="name"
                [class.tl-input--invalid]="form.controls.name.touched && form.controls.name.invalid"
              />
              @if (form.controls.name.touched && form.controls.name.invalid) {
                <span class="tl-error">{{ 'clinicAdmin.common.required' | t }}</span>
              }
            </div>

            <div class="tl-field">
              <label class="tl-label" for="service-code">{{ 'clinicAdmin.services.productCode' | t }}</label>
              <input id="service-code" class="tl-input" formControlName="productCode" />
            </div>
          </div>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="service-category">
            {{ 'clinicAdmin.services.category' | t }} <span class="required">*</span>
          </label>
          <input
            id="service-category"
            class="tl-input"
            formControlName="specialty"
            list="service-categories"
            [class.tl-input--invalid]="form.controls.specialty.touched && form.controls.specialty.invalid"
          />
          <datalist id="service-categories">
            <option value="Preventive"></option>
            <option value="Implants"></option>
            <option value="Endodontics"></option>
            <option value="Orthodontics"></option>
            <option value="Paediatric"></option>
          </datalist>
          @if (form.controls.specialty.touched && form.controls.specialty.invalid) {
            <span class="tl-error">{{ 'clinicAdmin.common.required' | t }}</span>
          }
        </div>

        <div class="tl-field">
          <label class="tl-label" for="service-subcategory">{{ 'clinicAdmin.services.subCategory' | t }}</label>
          <input id="service-subcategory" class="tl-input" formControlName="subCategory" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="service-clinic">{{ 'clinicAdmin.services.clinic' | t }}</label>
          <select id="service-clinic" class="tl-select--field" formControlName="clinicId">
            <option value="">{{ 'clinicAdmin.services.chooseClinic' | t }}</option>
            @for (location of locations(); track location.id) {
              <option [value]="location.id">{{ location.name }}</option>
            }
          </select>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="service-price">{{ 'clinicAdmin.services.price' | t }}</label>
          <input id="service-price" class="tl-input" type="number" min="0" step="0.01" formControlName="price" />
          @if (form.controls.price.touched && form.controls.price.invalid) {
            <span class="tl-error">{{ 'clinicAdmin.services.priceHint' | t }}</span>
          }
        </div>

        <div class="tl-field">
          <label class="tl-label" for="service-duration">{{ 'clinicAdmin.services.duration' | t }}</label>
          <input id="service-duration" class="tl-input" type="number" min="5" max="480" formControlName="durationMinutes" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="service-slot">{{ 'clinicAdmin.services.timeSlot' | t }}</label>
          <input id="service-slot" class="tl-input" type="number" min="5" max="480" formControlName="timeSlotMinutes" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="service-type">{{ 'clinicAdmin.services.type' | t }}</label>
          <select id="service-type" class="tl-select--field" formControlName="type">
            @for (option of serviceTypes; track option) {
              <option [value]="option">{{ typeKey(option) | t }}</option>
            }
          </select>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="service-discount">{{ 'clinicAdmin.services.discount' | t }}</label>
          <input id="service-discount" class="tl-input" type="number" min="0" max="100" step="0.01" formControlName="discount" />
          @if (form.controls.discount.touched && form.controls.discount.invalid) {
            <span class="tl-error">{{ 'clinicAdmin.services.discountHint' | t }}</span>
          }
        </div>

        <div class="tl-field">
          <span class="tl-label">{{ 'clinicAdmin.common.status' | t }}</span>
          <div class="tl-switch-row">
            <span class="tl-switch-label">
              {{ (form.controls.isActive.value ? 'clinicAdmin.common.active' : 'clinicAdmin.common.inactive') | t }}
            </span>
            <label class="tl-switch">
              <input type="checkbox" formControlName="isActive" />
              <span class="tl-slider"></span>
            </label>
          </div>
        </div>

        <div class="tl-field">
          <span class="tl-label">{{ 'clinicAdmin.services.advancedPayment' | t }}</span>
          <div class="tl-switch-row">
            <span class="tl-switch-label">
              {{ (form.controls.advancedPayment.value ? 'clinicAdmin.common.yes' : 'clinicAdmin.common.no') | t }}
            </span>
            <label class="tl-switch">
              <input type="checkbox" formControlName="advancedPayment" />
              <span class="tl-slider"></span>
            </label>
          </div>
        </div>

        <div class="tl-field tl-field--full">
          <label class="tl-label" for="service-description">{{ 'clinicAdmin.services.description' | t }}</label>
          <textarea id="service-description" class="tl-textarea" formControlName="description"></textarea>
        </div>

        <div class="tl-field tl-field--full" style="border-top:1px solid var(--line);padding-top:0.75rem;margin-top:0.35rem;">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.45rem;">
            <div>
              <span class="tl-label">Required Inventory Materials (BOM)</span>
              <small style="display:block;color:var(--mute);font-size:0.74rem;">
                Automatically deducted when a procedure or appointment is marked Completed.
              </small>
            </div>
            <button type="button" class="tl-btn tl-btn-gray" style="padding:0.25rem 0.6rem;font-size:0.78rem;" (click)="addBomRow()">
              + Add Material
            </button>
          </div>
          @if (bomRows().length === 0) {
            <div style="font-size:0.8rem;color:var(--mute);padding:0.45rem 0.6rem;background:var(--bg);border-radius:6px;">
              No consumables linked to this service yet.
            </div>
          } @else {
            <div style="display:flex;flex-direction:column;gap:0.45rem;">
              @for (bom of bomRows(); track $index; let idx = $index) {
                <div style="display:grid;grid-template-columns:1fr 110px auto;gap:0.45rem;align-items:center;">
                  <select
                    class="tl-select--field"
                    [ngModel]="bom.itemId"
                    [ngModelOptions]="{ standalone: true }"
                    (ngModelChange)="updateBomItem(idx, $event)"
                  >
                    <option value="">Select inventory material...</option>
                    @for (item of inventoryItems(); track item.id) {
                      <option [value]="item.id">
                        {{ item.name }} ({{ item.quantityOnHand }} {{ item.unitOfMeasure }})
                      </option>
                    }
                  </select>
                  <input
                    type="number"
                    class="tl-input"
                    min="0.01"
                    step="0.01"
                    [ngModel]="bom.quantityRequired"
                    [ngModelOptions]="{ standalone: true }"
                    (ngModelChange)="updateBomQty(idx, $event)"
                    placeholder="Qty"
                  />
                  <button
                    type="button"
                    class="tl-btn tl-btn-gray"
                    style="padding:0.3rem 0.55rem;color:#c62828;"
                    (click)="removeBomRow(idx)"
                    aria-label="Remove material"
                  >
                    ✕
                  </button>
                </div>
              }
            </div>
          }
        </div>
      </form>

      <div drawerFooter>
        <button type="button" class="tl-btn tl-btn-gray" (click)="closeDrawer()" [disabled]="saving()">
          {{ 'clinicAdmin.common.cancel' | t }}
        </button>
        <button type="submit" form="clinic-service-form" class="tl-btn tl-btn-teal" [disabled]="saving()">
          {{ (saving() ? 'clinicAdmin.common.saving' : 'clinicAdmin.common.save') | t }}
        </button>
      </div>
    </fe-tl-drawer>

    @if (inventoryModalOpen()) {
      <div class="tl-modal-backdrop" (click)="inventoryModalOpen.set(false)">
        <div class="tl-modal-card" (click)="$event.stopPropagation()">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.75rem;">
            <h3 style="margin:0;font-size:1.05rem;">
              {{ editingInventoryItem() ? 'Edit Inventory Material' : 'Add Branch Inventory Material' }}
            </h3>
            <button type="button" class="tl-btn tl-btn-gray" (click)="inventoryModalOpen.set(false)">✕</button>
          </div>
          <div class="tl-form-grid">
            <div class="tl-field">
              <label class="tl-label">Material Name *</label>
              <input class="tl-input" [(ngModel)]="invFormName" placeholder="e.g., Septocaine 4% Carpule" />
            </div>
            <div class="tl-field">
              <label class="tl-label">SKU / Code</label>
              <input class="tl-input" [(ngModel)]="invFormSku" placeholder="SKU-001" />
            </div>
            <div class="tl-field">
              <label class="tl-label">Category</label>
              <select class="tl-select--field" [(ngModel)]="invFormCategory">
                @for (cat of inventoryCategories; track cat) {
                  <option [value]="cat">{{ cat }}</option>
                }
              </select>
            </div>
            <div class="tl-field">
              <label class="tl-label">Branch</label>
              <select class="tl-select--field" [(ngModel)]="invFormClinicId">
                <option value="">Project Default Branch</option>
                @for (loc of locations(); track loc.id) {
                  <option [value]="loc.id">{{ loc.name }}</option>
                }
              </select>
            </div>
            <div class="tl-field">
              <label class="tl-label">Unit of Measure</label>
              <input class="tl-input" [(ngModel)]="invFormUnit" placeholder="pcs / ml / syringe / box" />
            </div>
            <div class="tl-field">
              <label class="tl-label">Unit Cost (JOD)</label>
              <input class="tl-input" type="number" min="0" step="0.001" [(ngModel)]="invFormUnitCost" />
            </div>
            @if (!editingInventoryItem()) {
              <div class="tl-field">
                <label class="tl-label">Initial Stock Quantity</label>
                <input class="tl-input" type="number" min="0" step="0.01" [(ngModel)]="invFormQty" />
              </div>
            }
            <div class="tl-field">
              <label class="tl-label">Minimum Stock Alert Threshold</label>
              <input class="tl-input" type="number" min="0" step="0.01" [(ngModel)]="invFormMinThreshold" />
            </div>
            @if (!editingInventoryItem() && invFormQty > 0 && invFormUnitCost > 0) {
              <div class="tl-field tl-field--full">
                <label style="display:inline-flex;align-items:center;gap:0.45rem;font-size:0.84rem;font-weight:600;">
                  <input type="checkbox" [(ngModel)]="invFormRecordExpense" />
                  Record initial stock purchase as Cash-Basis Branch Expense (INVENTORY_PURCHASE)
                </label>
              </div>
            }
          </div>
          <div style="display:flex;justify-content:flex-end;gap:0.5rem;margin-top:1rem;">
            <button type="button" class="tl-btn tl-btn-gray" (click)="inventoryModalOpen.set(false)">Cancel</button>
            <button type="button" class="tl-btn tl-btn-teal" (click)="saveInventoryItem()" [disabled]="inventorySaving()">
              {{ inventorySaving() ? 'Saving...' : 'Save Material' }}
            </button>
          </div>
        </div>
      </div>
    }

    @if (adjustModalOpen() && adjustingItem(); as targetItem) {
      <div class="tl-modal-backdrop" (click)="adjustModalOpen.set(false)">
        <div class="tl-modal-card" (click)="$event.stopPropagation()">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.75rem;">
            <h3 style="margin:0;font-size:1.05rem;">Restock / Adjust: {{ targetItem.name }}</h3>
            <button type="button" class="tl-btn tl-btn-gray" (click)="adjustModalOpen.set(false)">✕</button>
          </div>
          <p style="margin:0 0 0.75rem;font-size:0.84rem;color:var(--mute);">
            Current Stock: <strong>{{ targetItem.quantityOnHand }} {{ targetItem.unitOfMeasure }}</strong>
          </p>
          <div class="tl-form-grid">
            <div class="tl-field">
              <label class="tl-label">Transaction Type</label>
              <select class="tl-select--field" [(ngModel)]="adjFormType">
                <option value="MANUAL_PURCHASE">Restock Purchase (+)</option>
                <option value="MANUAL_ADJUSTMENT">Manual Stock Adjustment (+/-)</option>
              </select>
            </div>
            <div class="tl-field">
              <label class="tl-label">Quantity ({{ adjFormType === 'MANUAL_PURCHASE' ? 'Add' : 'Delta' }})</label>
              <input class="tl-input" type="number" step="0.01" [(ngModel)]="adjFormQty" />
            </div>
            <div class="tl-field">
              <label class="tl-label">Unit Cost (JOD)</label>
              <input class="tl-input" type="number" min="0" step="0.001" [(ngModel)]="adjFormUnitCost" />
            </div>
            <div class="tl-field tl-field--full">
              <label class="tl-label">Notes</label>
              <input class="tl-input" [(ngModel)]="adjFormNotes" placeholder="Supplier invoice # or stock count reason" />
            </div>
            @if (adjFormType === 'MANUAL_PURCHASE') {
              <div class="tl-field tl-field--full">
                <label style="display:inline-flex;align-items:center;gap:0.45rem;font-size:0.84rem;font-weight:600;">
                  <input type="checkbox" [(ngModel)]="adjFormRecordExpense" />
                  Log purchase total as Cash-Basis Branch Expense
                </label>
              </div>
            }
          </div>
          <div style="display:flex;justify-content:flex-end;gap:0.5rem;margin-top:1rem;">
            <button type="button" class="tl-btn tl-btn-gray" (click)="adjustModalOpen.set(false)">Cancel</button>
            <button type="button" class="tl-btn tl-btn-teal" (click)="submitAdjustStock()" [disabled]="inventorySaving()">
              {{ inventorySaving() ? 'Applying...' : 'Apply Adjustment' }}
            </button>
          </div>
        </div>
      </div>
    }

    @if (toast(); as notice) {
      <div class="tl-toast" [class.tl-toast--error]="notice.error" role="status">
        {{ notice.message }}
      </div>
    }
  `,
  styles: [`
    .tl-tabs-nav {
      display: flex;
      gap: 0.4rem;
      border-bottom: 1px solid var(--line);
      padding-bottom: 0.35rem;
      margin-bottom: 0.85rem;
      overflow-x: auto;
    }
    .tl-tab-btn {
      padding: 0.45rem 0.9rem;
      border-radius: 6px;
      border: 1px solid transparent;
      background: transparent;
      color: var(--mute);
      font-size: 0.84rem;
      font-weight: 600;
      cursor: pointer;
      white-space: nowrap;
    }
    .tl-tab-btn:hover { background: var(--line); color: var(--ink); }
    .tl-tab-btn--active {
      background: rgba(0, 137, 123, 0.1);
      color: #00796b;
      border-color: rgba(0, 137, 123, 0.25);
    }
    .tl-pill {
      display: inline-block;
      padding: 0.16rem 0.5rem;
      border-radius: 999px;
      font-size: 0.72rem;
      font-weight: 700;
    }
    .tl-pill--ok { background: #e8f5e9; color: #2e7d32; }
    .tl-pill--warn { background: #ffebee; color: #c62828; }
    .tl-pill--info { background: #e3f2fd; color: #1565c0; }
    .tl-modal-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(15, 23, 42, 0.45);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 1200;
      padding: 1rem;
    }
    .tl-modal-card {
      background: var(--surf, #fff);
      border-radius: 12px;
      padding: 1.25rem;
      width: min(560px, 100%);
      max-height: 90vh;
      overflow-y: auto;
      box-shadow: 0 20px 50px rgba(0,0,0,0.2);
    }
  `],
})
export class ClinicServicesComponent implements OnInit {
  private readonly api = inject(ClinicApi);
  private readonly patientsApi = inject(ClinicPatientApi);
  private readonly sessions = inject(SessionService);
  private readonly projects = inject(ProjectContextService);
  private readonly i18n = inject(I18nService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly cdr = inject(ChangeDetectorRef);
  protected readonly format = inject(FormatService);

  protected readonly canManage = computed(() => this.sessions.can()(PERMISSIONS.procedureManage));
  protected readonly serviceTypes = SERVICE_TYPES;
  protected readonly systemServices = SYSTEM_SERVICES;
  protected readonly inventoryCategories = INVENTORY_CATEGORIES;

  protected readonly activeTab = signal<ServicePageTab>('services');
  protected readonly rows = signal<readonly ClinicProcedure[]>([]);
  protected readonly locations = signal<readonly ClinicLocation[]>([]);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveFailed = signal(false);
  protected readonly saveRequestId = signal<string | null>(null);
  protected readonly saveErrorMessage = signal<string | null>(null);
  protected readonly drawerOpen = signal(false);
  protected readonly editing = signal<ClinicProcedure | null>(null);
  protected readonly toast = signal<Toast | null>(null);
  protected readonly searchTerm = signal('');
  protected readonly statusFilter = signal<StatusFilter>('');
  protected readonly bulkAction = signal<BulkAction>('');
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  protected readonly page = signal(1);
  protected readonly pageSize = signal(10);

  // Req 1: BOM & Branch Inventory State
  protected readonly bomRows = signal<ServiceMaterialInput[]>([]);
  protected readonly inventoryItems = signal<readonly InventoryItemRow[]>([]);
  protected readonly inventoryLoading = signal(false);
  protected readonly inventorySaving = signal(false);
  protected readonly inventorySearch = signal('');
  protected readonly inventoryClinicFilter = signal('');
  protected readonly inventoryLowStockOnly = signal(false);
  protected readonly consolidatedItems = signal<readonly ConsolidatedInventoryRow[]>([]);
  protected readonly consolidatedLoading = signal(false);
  protected readonly consolidatedSearch = signal('');
  protected readonly inventoryTxList = signal<readonly InventoryTransactionRow[]>([]);
  protected readonly transactionsLoading = signal(false);
  protected readonly transactionItemFilter = signal('');

  protected readonly inventoryModalOpen = signal(false);
  protected readonly editingInventoryItem = signal<InventoryItemRow | null>(null);
  protected invFormName = '';
  protected invFormSku = '';
  protected invFormCategory = 'CONSUMABLE';
  protected invFormClinicId = '';
  protected invFormUnit = 'pcs';
  protected invFormUnitCost = 0;
  protected invFormQty = 0;
  protected invFormMinThreshold = 5;
  protected invFormRecordExpense = true;

  protected readonly adjustModalOpen = signal(false);
  protected readonly adjustingItem = signal<InventoryItemRow | null>(null);
  protected adjFormType = 'MANUAL_PURCHASE';
  protected adjFormQty = 1;
  protected adjFormUnitCost = 0;
  protected adjFormNotes = '';
  protected adjFormRecordExpense = true;

  protected readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(160)]],
    specialty: ['', [Validators.required, Validators.maxLength(120)]],
    durationMinutes: [30, [Validators.min(5), Validators.max(480)]],
    bufferMinutes: [0, [Validators.min(0), Validators.max(240)]],
    price: [null as number | null, [Validators.min(0)]],
    isActive: [true],
    productCode: [''],
    subCategory: [''],
    clinicId: [''],
    timeSlotMinutes: [null as number | null, [Validators.min(5), Validators.max(480)]],
    type: ['IN_CLINIC'],
    description: [''],
    discount: [null as number | null, [Validators.min(0), Validators.max(100)]],
    imageUrl: [''],
    systemService: [''],
    advancedPayment: [false],
  });

  protected readonly filteredRows = computed(() => {
    const term = this.searchTerm().trim().toLowerCase();
    const status = this.statusFilter();
    return this.rows().filter((row) => {
      const matchesTerm =
        term === '' ||
        [row.name, row.specialty, row.productCode, row.subCategory, row.clinicName, row.systemService]
          .filter((value): value is string => typeof value === 'string')
          .some((value) => value.toLowerCase().includes(term));
      const matchesStatus =
        status === '' || (status === 'ACTIVE' ? row.isActive === true : row.isActive !== true);
      return matchesTerm && matchesStatus;
    });
  });

  protected readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.filteredRows().length / this.pageSize())),
  );

  protected readonly pagedRows = computed(() => {
    const start = (this.page() - 1) * this.pageSize();
    return this.filteredRows().slice(start, start + this.pageSize());
  });

  protected readonly pageNumbers = computed(() => {
    const count = this.pageCount();
    const current = this.page();
    const first = Math.max(1, Math.min(current - 2, count - 4));
    return Array.from({ length: Math.min(5, count) }, (_, index) => first + index);
  });

  protected readonly allPageSelected = computed(
    () =>
      this.pagedRows().length > 0 &&
      this.pagedRows().every((row) => row.id !== undefined && this.selectedIds().has(row.id)),
  );

  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.i18n.register({ clinicAdmin: clinicAdminStrings as Namespace });
    this.destroyRef.onDestroy(() => {
      if (this.toastTimer) clearTimeout(this.toastTimer);
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      const [procedures, locations, invItems] = await Promise.all([
        this.api.procedures({ projectId: this.projectId() }),
        this.api.locations({ projectId: this.projectId() }),
        this.patientsApi.inventoryItems({ projectId: this.projectId() }).catch(() => []),
      ]);
      this.rows.set(procedures.items ?? []);
      this.locations.set(locations.items ?? []);
      this.inventoryItems.set(invItems ?? []);
      this.page.set(1);
      this.selectedIds.set(new Set());
    } catch {
      this.loadFailed.set(true);
      this.rows.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  protected switchTab(tab: ServicePageTab): void {
    this.activeTab.set(tab);
    if (tab === 'inventory') {
      void this.loadInventory();
    } else if (tab === 'consolidated') {
      void this.loadConsolidated();
    } else if (tab === 'transactions') {
      void this.loadTransactions();
    }
  }

  protected async loadInventory(): Promise<void> {
    this.inventoryLoading.set(true);
    try {
      const items = await this.patientsApi.inventoryItems({
        projectId: this.projectId(),
        clinicId: this.inventoryClinicFilter() || undefined,
        q: this.inventorySearch().trim() || undefined,
        lowStockOnly: this.inventoryLowStockOnly() || undefined,
      });
      this.inventoryItems.set(items ?? []);
    } catch {
      this.showRawToast('Failed to load branch inventory.', true);
    } finally {
      this.inventoryLoading.set(false);
    }
  }

  protected async loadConsolidated(): Promise<void> {
    this.consolidatedLoading.set(true);
    try {
      const items = await this.patientsApi.consolidatedInventory({
        projectId: this.projectId(),
        q: this.consolidatedSearch().trim() || undefined,
      });
      this.consolidatedItems.set(items ?? []);
    } catch {
      this.showRawToast('Failed to load consolidated inventory.', true);
    } finally {
      this.consolidatedLoading.set(false);
    }
  }

  protected async loadTransactions(): Promise<void> {
    this.transactionsLoading.set(true);
    try {
      const txs = await this.patientsApi.inventoryTransactions({
        projectId: this.projectId(),
        itemId: this.transactionItemFilter() || undefined,
      });
      this.inventoryTxList.set(txs ?? []);
    } catch {
      this.showRawToast('Failed to load inventory audit log.', true);
    } finally {
      this.transactionsLoading.set(false);
    }
  }

  protected addBomRow(): void {
    const defaultItem = this.inventoryItems()[0]?.id ?? '';
    this.bomRows.update((list) => [...list, { itemId: defaultItem, quantityRequired: 1 }]);
  }

  protected updateBomItem(index: number, itemId: string): void {
    this.bomRows.update((list) =>
      list.map((row, idx) => (idx === index ? { ...row, itemId } : row)),
    );
  }

  protected updateBomQty(index: number, qty: number | string): void {
    const parsed = Math.max(0.01, Number(qty) || 1);
    this.bomRows.update((list) =>
      list.map((row, idx) => (idx === index ? { ...row, quantityRequired: parsed } : row)),
    );
  }

  protected removeBomRow(index: number): void {
    this.bomRows.update((list) => list.filter((_, idx) => idx !== index));
  }

  protected openCreateInventory(): void {
    if (!this.canManage()) return;
    this.editingInventoryItem.set(null);
    this.invFormName = '';
    this.invFormSku = '';
    this.invFormCategory = 'CONSUMABLE';
    this.invFormClinicId = this.inventoryClinicFilter() || this.locations()[0]?.id || '';
    this.invFormUnit = 'pcs';
    this.invFormUnitCost = 0;
    this.invFormQty = 0;
    this.invFormMinThreshold = 5;
    this.invFormRecordExpense = true;
    this.inventoryModalOpen.set(true);
  }

  protected openEditInventory(item: InventoryItemRow): void {
    if (!this.canManage()) return;
    this.editingInventoryItem.set(item);
    this.invFormName = item.name;
    this.invFormSku = item.sku ?? '';
    this.invFormCategory = item.category;
    this.invFormClinicId = item.clinicId ?? '';
    this.invFormUnit = item.unitOfMeasure;
    this.invFormUnitCost = item.unitCost;
    this.invFormQty = item.quantityOnHand;
    this.invFormMinThreshold = item.minStockThreshold;
    this.invFormRecordExpense = false;
    this.inventoryModalOpen.set(true);
  }

  protected async saveInventoryItem(): Promise<void> {
    const name = this.invFormName.trim();
    if (!name) {
      this.showRawToast('Material name is required.', true);
      return;
    }
    this.inventorySaving.set(true);
    try {
      const current = this.editingInventoryItem();
      if (current) {
        await this.patientsApi.updateInventoryItem(
          current.id,
          {
            name,
            sku: this.invFormSku.trim() || null,
            category: this.invFormCategory,
            unitOfMeasure: this.invFormUnit.trim() || 'pcs',
            unitCost: Number(this.invFormUnitCost) || 0,
            minStockThreshold: Number(this.invFormMinThreshold) || 0,
            clinicId: this.invFormClinicId || null,
          },
          { projectId: this.projectId() },
        );
        this.showRawToast('Inventory material updated.', false);
      } else {
        const payload: CreateInventoryItemPayload = {
          name,
          sku: this.invFormSku.trim() || null,
          category: this.invFormCategory,
          unitOfMeasure: this.invFormUnit.trim() || 'pcs',
          unitCost: Number(this.invFormUnitCost) || 0,
          quantityOnHand: Number(this.invFormQty) || 0,
          minStockThreshold: Number(this.invFormMinThreshold) || 0,
          clinicId: this.invFormClinicId || null,
          recordPurchaseExpense: this.invFormRecordExpense,
          projectId: this.projectId(),
        };
        await this.patientsApi.createInventoryItem(payload, { projectId: this.projectId() });
        this.showRawToast('Inventory material created.', false);
      }
      this.inventoryModalOpen.set(false);
      await this.loadInventory();
    } catch (err) {
      this.showRawToast(err instanceof Error ? err.message : 'Failed to save inventory item.', true);
    } finally {
      this.inventorySaving.set(false);
    }
  }

  protected openAdjustStock(item: InventoryItemRow): void {
    if (!this.canManage()) return;
    this.adjustingItem.set(item);
    this.adjFormType = 'MANUAL_PURCHASE';
    this.adjFormQty = 1;
    this.adjFormUnitCost = item.unitCost ?? 0;
    this.adjFormNotes = '';
    this.adjFormRecordExpense = true;
    this.adjustModalOpen.set(true);
  }

  protected async submitAdjustStock(): Promise<void> {
    const target = this.adjustingItem();
    if (!target) return;
    this.inventorySaving.set(true);
    try {
      const payload: AdjustInventoryStockPayload = {
        transactionType: this.adjFormType,
        quantity: Number(this.adjFormQty) || 0,
        unitCost: Number(this.adjFormUnitCost) || 0,
        notes: this.adjFormNotes.trim() || undefined,
        recordPurchaseExpense: this.adjFormType === 'MANUAL_PURCHASE' ? this.adjFormRecordExpense : false,
        projectId: this.projectId(),
      };
      await this.patientsApi.adjustInventoryStock(target.id, payload, { projectId: this.projectId() });
      this.adjustModalOpen.set(false);
      this.showRawToast('Stock updated successfully.', false);
      await this.loadInventory();
    } catch (err) {
      this.showRawToast(err instanceof Error ? err.message : 'Failed to adjust stock.', true);
    } finally {
      this.inventorySaving.set(false);
    }
  }

  protected async deleteInventory(item: InventoryItemRow): Promise<void> {
    if (!this.canManage()) return;
    try {
      await this.patientsApi.deleteInventoryItem(item.id, { projectId: this.projectId() });
      this.showRawToast('Inventory material deleted.', false);
      await this.loadInventory();
    } catch {
      this.showRawToast('Failed to delete inventory material.', true);
    }
  }

  protected onSearch(value: string): void {
    this.searchTerm.set(value);
    this.page.set(1);
  }

  protected onStatusChange(value: string): void {
    this.statusFilter.set(value === 'ACTIVE' || value === 'INACTIVE' ? value : '');
    this.page.set(1);
  }

  protected onBulkAction(value: string): void {
    this.bulkAction.set(value === 'ACTIVE' || value === 'INACTIVE' ? value : '');
  }

  protected openCreate(): void {
    if (!this.canManage()) return;
    this.editing.set(null);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    this.bomRows.set([]);
    if (this.inventoryItems().length === 0) {
      void this.loadInventory();
    }
    this.form.reset({
      name: '',
      specialty: '',
      durationMinutes: 30,
      bufferMinutes: 0,
      price: null,
      isActive: true,
      productCode: '',
      subCategory: '',
      clinicId: this.locations()[0]?.id ?? '',
      timeSlotMinutes: null,
      type: 'IN_CLINIC',
      description: '',
      discount: null,
      imageUrl: '',
      systemService: '',
      advancedPayment: false,
    });
    this.drawerOpen.set(true);
  }

  protected openEdit(row: ClinicProcedure): void {
    if (!this.canManage()) return;
    this.editing.set(row);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    this.bomRows.set([]);
    if (this.inventoryItems().length === 0) {
      void this.loadInventory();
    }
    if (row.id) {
      const projId = (row as any)?.projectId || this.projectId();
      void this.patientsApi
        .getProcedureMaterials(row.id, { projectId: projId })
        .then((mats) =>
          this.bomRows.set(
            (mats ?? []).map((m) => ({ itemId: m.itemId, quantityRequired: m.quantityRequired })),
          ),
        )
        .catch(() => this.bomRows.set([]));
    }
    this.form.reset({
      name: row.name ?? '',
      specialty: row.specialty ?? '',
      durationMinutes: row.durationMinutes ?? 30,
      bufferMinutes: row.bufferMinutes ?? 0,
      price: row.price ?? null,
      isActive: row.isActive !== false,
      productCode: row.productCode ?? '',
      subCategory: row.subCategory ?? '',
      clinicId: row.clinicId ?? '',
      timeSlotMinutes: row.timeSlotMinutes ?? null,
      type: row.type ?? 'IN_CLINIC',
      description: row.description ?? '',
      discount: row.discount ?? null,
      imageUrl: row.imageUrl ?? '',
      systemService: row.systemService ?? '',
      advancedPayment: row.advancedPayment === true,
    });
    this.drawerOpen.set(true);
  }

  protected closeDrawer(): void {
    if (this.saving()) return;
    this.drawerOpen.set(false);
  }

  protected async save(): Promise<void> {
    if (!this.canManage()) {
      this.showToast('clinicAdmin.common.permissionDenied', true);
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    const payload = this.toPayload();
    try {
      const current = this.editing();
      const projId = (current as any)?.projectId || this.projectId();
      const savedProc = current?.id
        ? await this.api.updateProcedure(current.id, payload, projId)
        : await this.api.createProcedure(payload, projId);
      const procId = savedProc?.id || current?.id;
      if (procId) {
        const cleanBom = this.bomRows().filter((b) => b.itemId && b.quantityRequired > 0);
        await this.patientsApi.saveProcedureMaterials(procId, cleanBom, { projectId: projId });
      }
      this.drawerOpen.set(false);
      await this.load();
      this.showToast('clinicAdmin.common.updated', false);
    } catch (cause) {
      this.saveFailed.set(true);
      if (cause instanceof ApiError) {
        this.saveRequestId.set(cause.requestId ?? null);
        this.saveErrorMessage.set(cause.message ?? null);
      } else if (cause instanceof Error) {
        this.saveRequestId.set(null);
        this.saveErrorMessage.set(cause.message);
      } else {
        this.saveRequestId.set(null);
        this.saveErrorMessage.set(null);
      }
    } finally {
      this.saving.set(false);
    }
  }

  protected async toggleActive(
    row: ClinicProcedure,
    next: boolean,
    input: HTMLInputElement,
  ): Promise<void> {
    if (!this.canManage() || !row.id) {
      input.checked = row.isActive === true;
      return;
    }
    input.disabled = true;
    try {
      await this.api.updateProcedure(row.id, { isActive: next }, this.projectId());
      this.rows.update((rows) =>
        rows.map((candidate) =>
          candidate.id === row.id ? { ...candidate, isActive: next } : candidate,
        ),
      );
      this.showToast('clinicAdmin.common.updated', false);
    } catch {
      input.checked = row.isActive === true;
      this.showToast('clinicAdmin.common.saveFailed', true);
    } finally {
      input.disabled = false;
    }
  }

  protected async applyBulk(): Promise<void> {
    const action = this.bulkAction();
    const selected = this.selectedIds();
    if (!this.canManage() || action === '' || selected.size === 0) return;
    const targets = this.rows().filter((row) => row.id !== undefined && selected.has(row.id));
    try {
      await Promise.all(
        targets.map((row) =>
          this.api.updateProcedure(row.id!, { isActive: action === 'ACTIVE' }, this.projectId()),
        ),
      );
      await this.load();
      this.bulkAction.set('');
      this.showToast('clinicAdmin.common.updated', false);
    } catch {
      this.showToast('clinicAdmin.common.saveFailed', true);
    }
  }

  protected toggleSelection(id: string | undefined, checked: boolean): void {
    if (!id) return;
    const next = new Set(this.selectedIds());
    if (checked) next.add(id);
    else next.delete(id);
    this.selectedIds.set(next);
  }

  protected toggleAll(checked: boolean): void {
    const next = new Set(this.selectedIds());
    for (const row of this.pagedRows()) {
      if (!row.id) continue;
      if (checked) next.add(row.id);
      else next.delete(row.id);
    }
    this.selectedIds.set(next);
  }

  protected isSelected(id: string | undefined): boolean {
    return id !== undefined && this.selectedIds().has(id);
  }

  protected goToPage(number: number): void {
    if (number < 1 || number > this.pageCount()) return;
    this.page.set(number);
  }

  protected showingFrom(): number {
    return this.filteredRows().length === 0 ? 0 : (this.page() - 1) * this.pageSize() + 1;
  }

  protected showingTo(): number {
    return Math.min(this.page() * this.pageSize(), this.filteredRows().length);
  }

  protected typeKey(type: string): string {
    if (type === 'ONLINE') return 'clinicAdmin.services.typeOnline';
    if (type === 'HOME_VISIT') return 'clinicAdmin.services.typeHomeVisit';
    return 'clinicAdmin.services.typeInClinic';
  }

  protected onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.readImage(file);
    input.value = '';
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    const file = event.dataTransfer?.files?.[0];
    if (file) this.readImage(file);
  }

  protected removeImage(): void {
    this.form.controls.imageUrl.setValue('');
  }

  protected exportCsv(): void {
    const header = ['Service', 'Product Code', 'Price', 'Duration', 'Category', 'Clinic', 'Status'];
    const lines = [
      header,
      ...this.filteredRows().map((row) => [
        row.name ?? '',
        row.productCode ?? '',
        row.price === undefined || row.price === null ? '' : String(row.price),
        String(row.durationMinutes ?? ''),
        row.specialty ?? '',
        row.clinicName ?? '',
        row.isActive ? 'ACTIVE' : 'INACTIVE',
      ]),
    ];
    const csv = lines.map((line) => line.map(csvCell).join(',')).join('\r\n');
    const blob = new Blob([`\ufeff${csv}`], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'services.csv';
    anchor.click();
    URL.revokeObjectURL(url);
  }

  private readImage(file: File): void {
    if (!file.type.startsWith('image/')) {
      this.showToast('clinicAdmin.common.imageType', true);
      return;
    }
    if (file.size > 1_000_000) {
      this.showToast('clinicAdmin.common.imageTooLarge', true);
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string') {
        this.form.controls.imageUrl.setValue(reader.result);
        this.cdr.markForCheck();
      }
    };
    reader.readAsDataURL(file);
  }

  private toPayload(): ClinicProcedureInput {
    const value = this.form.getRawValue();
    return {
      name: (value.name ?? '').trim(),
      specialty: (value.specialty ?? '').trim(),
      durationMinutes: value.durationMinutes ?? 30,
      bufferMinutes: value.bufferMinutes ?? 0,
      price: value.price,
      isActive: value.isActive ?? true,
      productCode: blankToNull(value.productCode),
      subCategory: blankToNull(value.subCategory),
      clinicId: blankToNull(value.clinicId),
      timeSlotMinutes: value.timeSlotMinutes,
      type: value.type ?? 'IN_CLINIC',
      description: blankToNull(value.description),
      discount: value.discount,
      imageUrl: blankToNull(value.imageUrl),
      systemService: blankToNull(value.systemService),
      advancedPayment: value.advancedPayment ?? false,
    };
  }

  protected projectId(): string | undefined {
    return this.projects.selectedProjectId() ?? this.sessions.session()?.projectIds?.[0] ?? undefined;
  }

  private showToast(messageKey: string, error: boolean): void {
    this.toast.set({ message: this.i18n.t(messageKey), error });
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toast.set(null), 3200);
  }

  private showRawToast(message: string, error: boolean): void {
    this.toast.set({ message, error });
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toast.set(null), 3400);
  }
}

function blankToNull(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

function csvCell(value: string): string {
  return `"${value.replaceAll('"', '""')}"`;
}
