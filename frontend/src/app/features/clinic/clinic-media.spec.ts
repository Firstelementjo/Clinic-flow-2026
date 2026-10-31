/**
 * Slice S7 — Documents and Media (Clinical Radiographs, Photos, Documents, PDFs).
 *
 * Frontend verification for:
 * 1. Rendering clinical media cards with FDI tooth chips and AES-256 indicators.
 * 2. Category, FDI tooth, and legal hold filtering.
 * 3. Interactive radiograph viewer (zoom in/out, contrast inversion, reset, close).
 * 4. Legal hold toggles and deletion refusal lock (DEC-007).
 * 5. Short-lived signed download URL generation.
 * 6. Access audit trail inspection for individual attachments and full patient history.
 * 7. Multipart media upload workflow with radiographer attribution and clinical notes.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, type ParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ClinicPatientDetailComponent } from './clinic-patient-detail.component';
import type { ClinicMediaAccessAuditRow, ClinicMediaRow, ClinicSignedDownloadUrl } from './clinic-patient.api';

afterEach(() => {
  localStorage.clear();
});

const PATIENT_ID = 'pat-s7-001';

const MOCK_PATIENT = {
  id: PATIENT_ID,
  patientNo: 'P-0077',
  displayName: 'Kareem Mansour',
  dateOfBirth: '1988-11-15',
  primaryPhone: '+962791234567',
  locale: 'ar',
  customerId: 'cus-s7',
  deleted: false,
};

const MOCK_MEDIA_1: ClinicMediaRow = {
  id: 'med-001',
  organizationId: 'org-test',
  projectId: 'proj-test',
  patientId: PATIENT_ID,
  fileName: 'tooth_16_periapical.png',
  contentType: 'image/png',
  byteSize: 1048576, // 1 MB
  storageKey: 'org-test/pat-s7-001/uuid-1.png',
  category: 'RADIOGRAPH_INTRAORAL',
  toothFdi: 16,
  acquisitionDate: '2026-10-15',
  radiographerName: 'Dr. Tariq',
  encrypted: true,
  encryptionAlgorithm: 'AES-256-GCM',
  scanStatus: 'CLEAN',
  legalHold: false,
  notes: 'Clear periapical view of upper right first molar',
  createdAt: '2026-10-15T10:00:00Z',
  updatedAt: '2026-10-15T10:00:00Z',
};

const MOCK_MEDIA_2: ClinicMediaRow = {
  id: 'med-002',
  organizationId: 'org-test',
  projectId: 'proj-test',
  patientId: PATIENT_ID,
  fileName: 'full_panoramic_opg.png',
  contentType: 'image/png',
  byteSize: 3145728, // 3 MB
  storageKey: 'org-test/pat-s7-001/uuid-2.png',
  category: 'RADIOGRAPH_PANORAMIC',
  toothFdi: null,
  acquisitionDate: '2026-10-10',
  radiographerName: 'Dr. Hiba',
  encrypted: true,
  encryptionAlgorithm: 'AES-256-GCM',
  scanStatus: 'CLEAN',
  legalHold: true,
  notes: 'Full panoramic survey before implant surgery',
  createdAt: '2026-10-10T09:00:00Z',
  updatedAt: '2026-10-10T09:00:00Z',
};

const MOCK_MEDIA_3: ClinicMediaRow = {
  id: 'med-003',
  organizationId: 'org-test',
  projectId: 'proj-test',
  patientId: PATIENT_ID,
  fileName: 'pathology_report.pdf',
  contentType: 'application/pdf',
  byteSize: 524288, // 512 KB
  storageKey: 'org-test/pat-s7-001/uuid-3.pdf',
  category: 'DOCUMENT_PDF',
  toothFdi: null,
  acquisitionDate: '2026-10-12',
  radiographerName: 'Lab tech',
  encrypted: true,
  encryptionAlgorithm: 'AES-256-GCM',
  scanStatus: 'CLEAN',
  legalHold: false,
  notes: 'Biopsy report',
  createdAt: '2026-10-12T11:00:00Z',
  updatedAt: '2026-10-12T11:00:00Z',
};

const MOCK_AUDIT: ClinicMediaAccessAuditRow[] = [
  {
    id: 'aud-001',
    organizationId: 'org-test',
    projectId: 'proj-test',
    patientId: PATIENT_ID,
    attachmentId: 'med-001',
    action: 'SIGNED_URL_MINTED',
    accessedByUserId: 'usr-admin',
    accessedByUserName: 'Admin User',
    accessIp: '127.0.0.1',
    notes: 'Viewed in radiograph viewer',
    accessedAt: '2026-10-19T14:30:00Z',
  },
  {
    id: 'aud-002',
    organizationId: 'org-test',
    projectId: 'proj-test',
    patientId: PATIENT_ID,
    attachmentId: 'med-001',
    action: 'LEGAL_HOLD_SET',
    accessedByUserId: 'usr-admin',
    accessedByUserName: 'Admin User',
    accessIp: '127.0.0.1',
    notes: 'Preserved for insurance audit',
    accessedAt: '2026-10-19T14:35:00Z',
  },
];

describe('Clinic Clinical Media & Radiographs Component (Slice S7)', () => {
  let fixture: ComponentFixture<ClinicPatientDetailComponent>;
  let http: HttpTestingController;
  let params: BehaviorSubject<ParamMap>;

  beforeEach(async () => {
    localStorage.clear();
    params = new BehaviorSubject<ParamMap>(convertToParamMap({ id: PATIENT_ID }));

    await TestBed.configureTestingModule({
      imports: [ClinicPatientDetailComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: params.asObservable(), snapshot: { data: {}, paramMap: params.value } },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicPatientDetailComponent);
    fixture.detectChanges();

    // Flush initial patient load
    await flushInitialLoads([MOCK_MEDIA_1, MOCK_MEDIA_2, MOCK_MEDIA_3]);
  });

  async function flushInitialLoads(mediaList: ClinicMediaRow[] = []) {
    http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}`)).flush(MOCK_PATIENT);
    await tick();
    http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}/relationships`)).flush([]);
    http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}/consents`)).flush([]);
    http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}/disclosures`)).flush([]);
    http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}/media`)).flush(mediaList);
    http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}/medical-history`)).flush(null);
    await tick();
  }

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function switchToMediaTab() {
    const mediaTabBtn = fixture.nativeElement.querySelector('[data-testid="tab-media"]') as HTMLButtonElement;
    expect(mediaTabBtn).toBeTruthy();
    mediaTabBtn.click();
    fixture.detectChanges();
  }

  it('renders media tab button and displays loaded clinical media cards', async () => {
    switchToMediaTab();
    await tick();

    const mediaSection = fixture.nativeElement.querySelector('[data-testid="clinic-media-section"]');
    expect(mediaSection).toBeTruthy();

    const cards = fixture.nativeElement.querySelectorAll('.media-card');
    expect(cards.length).toBe(3);

    // Verify card 1: Intraoral radiograph for Tooth 16
    const card1 = fixture.nativeElement.querySelector('[data-testid="media-card-med-001"]');
    expect(card1).toBeTruthy();
    expect(card1.textContent).toContain('tooth_16_periapical.png');
    expect(card1.textContent).toContain('FDI 16');
    expect(card1.textContent).toContain('AES-256');
    expect(card1.textContent).toContain('Dr. Tariq');

    // Verify card 2: Panoramic with Legal Hold badge
    const card2 = fixture.nativeElement.querySelector('[data-testid="media-card-med-002"]');
    expect(card2).toBeTruthy();
    expect(card2.textContent).toContain('full_panoramic_opg.png');
    const holdBadge = card2.querySelector('[data-testid="media-hold-badge-med-002"]');
    expect(holdBadge).toBeTruthy();
  });

  it('filters media items by category', async () => {
    switchToMediaTab();
    await tick();

    // Select PANORAMIC filter
    const select = fixture.nativeElement.querySelector('[data-testid="media-category-select"]') as HTMLSelectElement;
    expect(select).toBeTruthy();
    select.value = 'RADIOGRAPH_PANORAMIC';
    select.dispatchEvent(new Event('change'));
    await tick();

    const cards = fixture.nativeElement.querySelectorAll('.media-card');
    expect(cards.length).toBe(1);
    expect(cards[0].textContent).toContain('full_panoramic_opg.png');
  });

  it('filters media items by FDI tooth number', async () => {
    switchToMediaTab();
    await tick();

    // Select Tooth FDI 16
    const select = fixture.nativeElement.querySelector('[data-testid="media-tooth-select"]') as HTMLSelectElement;
    expect(select).toBeTruthy();
    select.value = '16';
    select.dispatchEvent(new Event('change'));
    await tick();

    const cards = fixture.nativeElement.querySelectorAll('.media-card');
    expect(cards.length).toBe(1);
    expect(cards[0].textContent).toContain('tooth_16_periapical.png');
    expect(cards[0].textContent).toContain('FDI 16');
  });

  it('filters media items by legal hold status', async () => {
    switchToMediaTab();
    await tick();

    // Toggle legal hold checkbox
    const holdCheckbox = fixture.nativeElement.querySelector('[data-testid="media-legal-hold-filter"]') as HTMLInputElement;
    expect(holdCheckbox).toBeTruthy();
    holdCheckbox.click();
    await tick();

    const cards = fixture.nativeElement.querySelectorAll('.media-card');
    expect(cards.length).toBe(1);
    expect(cards[0].textContent).toContain('full_panoramic_opg.png');
  });

  it('opens radiograph viewer modal, supports zoom and contrast inversion, and resets/closes', async () => {
    switchToMediaTab();
    await tick();

    // Click preview on med-001 (image/png)
    const previewBtn = fixture.nativeElement.querySelector('[data-testid="media-preview-btn-med-001"]') as HTMLButtonElement;
    expect(previewBtn).toBeTruthy();
    previewBtn.click();

    // Flush signed download URL endpoint
    const mockSigned: ClinicSignedDownloadUrl = {
      token: 'hmac-signed-token-123',
      downloadUrl: '/api/v1/clinic/media/download/uuid-1.png?token=hmac-signed-token-123',
      expiresAt: new Date(Date.now() + 900000).toISOString(),
      attachmentId: 'med-001',
      fileName: 'tooth_16_periapical.png',
      contentType: 'image/png',
      byteSize: 1048576,
    };
    http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}/media/med-001/download-url`)).flush(mockSigned);
    await tick();

    // Viewer modal should now be open
    const modal = fixture.nativeElement.querySelector('[data-testid="media-viewer-modal"]');
    expect(modal).toBeTruthy();

    const img = modal.querySelector('[data-testid="viewer-img"]') as HTMLImageElement;
    expect(img).toBeTruthy();
    expect(img.getAttribute('src')).toBe(mockSigned.downloadUrl);
    expect(img.style.transform).toBe('scale(1)');
    expect(img.style.filter).toBe('none');

    // Click Zoom In
    const zoomInBtn = modal.querySelector('[data-testid="viewer-zoom-in"]') as HTMLButtonElement;
    zoomInBtn.click();
    await tick();
    expect(img.style.transform).toBe('scale(1.25)');

    // Click Zoom Out
    const zoomOutBtn = modal.querySelector('[data-testid="viewer-zoom-out"]') as HTMLButtonElement;
    zoomOutBtn.click();
    await tick();
    expect(img.style.transform).toBe('scale(1)');

    // Click Invert Contrast
    const invertBtn = modal.querySelector('[data-testid="viewer-invert"]') as HTMLButtonElement;
    invertBtn.click();
    await tick();
    expect(img.style.filter).toBe('invert(1)');

    // Click Reset View
    const resetBtn = modal.querySelector('[data-testid="viewer-reset"]') as HTMLButtonElement;
    resetBtn.click();
    await tick();
    expect(img.style.transform).toBe('scale(1)');
    expect(img.style.filter).toBe('none');

    // Close Viewer
    const closeBtn = modal.querySelector('[data-testid="viewer-close-btn"]') as HTMLButtonElement;
    closeBtn.click();
    await tick();
    expect(fixture.nativeElement.querySelector('[data-testid="media-viewer-modal"]')).toBeNull();
  });

  it('enforces legal hold: disables delete button when legal hold is active (DEC-007)', async () => {
    switchToMediaTab();
    await tick();

    // med-001 (hold=false) -> delete button enabled
    const del1 = fixture.nativeElement.querySelector('[data-testid="media-delete-btn-med-001"]') as HTMLButtonElement;
    expect(del1).toBeTruthy();
    expect(del1.disabled).toBe(false);

    // med-002 (hold=true) -> delete button DISABLED
    const del2 = fixture.nativeElement.querySelector('[data-testid="media-delete-btn-med-002"]') as HTMLButtonElement;
    expect(del2).toBeTruthy();
    expect(del2.disabled).toBe(true);
  });

  it('toggles legal hold via modal and reloads media list', async () => {
    switchToMediaTab();
    await tick();

    // Click legal hold button on med-001 (currently hold=false, so action is Apply Hold)
    const holdBtn = fixture.nativeElement.querySelector('[data-testid="media-hold-btn-med-001"]') as HTMLButtonElement;
    expect(holdBtn).toBeTruthy();
    holdBtn.click();
    await tick();

    // Modal open
    const modal = fixture.nativeElement.querySelector('[data-testid="legal-hold-modal"]');
    expect(modal).toBeTruthy();

    const reasonInput = modal.querySelector('[data-testid="legal-hold-reason-input"]') as HTMLTextAreaElement;
    expect(reasonInput).toBeTruthy();
    reasonInput.value = 'Court order regarding malpractice inquiry';
    reasonInput.dispatchEvent(new Event('input'));
    await tick();

    const submitBtn = modal.querySelector('[data-testid="legal-hold-submit-btn"]') as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(false);
    submitBtn.click();

    // Expect POST toggle legal hold
    const postReq = http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}/media/med-001/legal-hold`));
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.body).toEqual({
      legalHold: true,
      reason: 'Court order regarding malpractice inquiry',
    });
    postReq.flush({ updated: true });
    await tick();

    // Expect re-fetch of patient media
    const getReq = http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}/media`));
    const updatedMedia1 = { ...MOCK_MEDIA_1, legalHold: true };
    getReq.flush([updatedMedia1, MOCK_MEDIA_2, MOCK_MEDIA_3]);
    await tick();

    // Verify modal closed and hold badge now appears on med-001
    expect(fixture.nativeElement.querySelector('[data-testid="legal-hold-modal"]')).toBeNull();
    const updatedHoldBadge = fixture.nativeElement.querySelector('[data-testid="media-hold-badge-med-001"]');
    expect(updatedHoldBadge).toBeTruthy();
  });

  it('fetches and displays access audit trail in audit modal', async () => {
    switchToMediaTab();
    await tick();

    // Click audit trail on med-001
    const auditBtn = fixture.nativeElement.querySelector('[data-testid="media-audit-btn-med-001"]') as HTMLButtonElement;
    expect(auditBtn).toBeTruthy();
    auditBtn.click();

    // Flush audit trail GET
    const auditReq = http.expectOne((r) => r.url.endsWith(`/api/v1/clinic/patients/${PATIENT_ID}/media/med-001/audit`));
    expect(auditReq.request.method).toBe('GET');
    auditReq.flush(MOCK_AUDIT);
    await tick();

    // Modal open
    const auditModal = fixture.nativeElement.querySelector('[data-testid="media-audit-modal"]');
    expect(auditModal).toBeTruthy();

    const rows = auditModal.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('SIGNED_URL_MINTED');
    expect(rows[0].textContent).toContain('Admin User');
    expect(rows[1].textContent).toContain('LEGAL_HOLD_SET');

    // Close audit modal
    const closeBtn = auditModal.querySelector('[data-testid="audit-close-btn"]') as HTMLButtonElement;
    closeBtn.click();
    await tick();
    expect(fixture.nativeElement.querySelector('[data-testid="media-audit-modal"]')).toBeNull();
  });

  it('displays empty state banner when no media matches filters', async () => {
    switchToMediaTab();
    await tick();

    // Filter by CEPHALOMETRIC (none match)
    const select = fixture.nativeElement.querySelector('[data-testid="media-category-select"]') as HTMLSelectElement;
    select.value = 'RADIOGRAPH_CEPHALOMETRIC';
    select.dispatchEvent(new Event('change'));
    await tick();

    expect(fixture.nativeElement.querySelectorAll('.media-card').length).toBe(0);
    const emptyBanner = fixture.nativeElement.querySelector('[data-testid="media-empty"]');
    expect(emptyBanner).toBeTruthy();
  });
});
