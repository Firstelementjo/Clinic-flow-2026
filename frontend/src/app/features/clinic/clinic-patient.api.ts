/**
 * The `ClinicPatientController` routes, typed for the clinic screens.
 *
 * ## Why this file is here and not in `core/api/endpoints.ts`
 *
 * `ClinicApi` in `core/api/endpoints.ts` is generated-model-typed and covers the seven
 * routes the committed OpenAPI document declares under `/api/v1/clinic`. The six routes
 * below exist in the backend (`ClinicPatientController`) and are **absent from the
 * committed document** â€” verified against `tests/contracts/openapi.json`, which lists
 * exactly seven clinic paths and none of these. `core/api` is not this phase's
 * ownership, and the endpoint layer is the right home for them the moment the contract
 * is re-promoted, so they are declared here, feature-scoped, and typed against the
 * Java records rather than against a document that does not describe them yet.
 *
 * **Follow-up for whoever owns the contract: regenerate
 * `tests/contracts/openapi.json` and `core/api/generated`, then move these six methods
 * onto `ClinicApi` and delete this file.** Leaving them here is a visible marker, not a
 * design.
 *
 * ## The shapes are the server's, not ours
 *
 * Every interface below mirrors a `record` in `crm-core` field for field:
 * `PatientModels.PatientRow`, `OdontogramModels.FindingRow` / `ChartEntry` /
 * `VocabularyTerm`, and `ScheduleModels.Slot`. Nothing is renamed, nothing is derived,
 * and nothing is given a value the server did not send.
 *
 * Every field is optional because Spring serialises with
 * `default-property-inclusion: non_null`: an absent field means *the value is null*,
 * which is a fact the screens render as an absence and never as a zero (FR-7.6).
 *
 * ## The vocabulary route is deliberately not called
 *
 * `GET /api/v1/clinic/odontogram/vocabulary` exists, but its javadoc says it "copies
 * the published template on first call" â€” it is a GET that **seeds a table**. A read
 * screen that triggers a write on page load is a decision this product does not get to
 * make by accident, so the odontogram screen renders the finding's own `code`, `scope`,
 * `state` and `source` exactly as the server sent them and does not translate them
 * through the catalogue. Recorded as an open question rather than solved here.
 */
import { Injectable, inject } from '@angular/core';
import { ApiService, type QueryParams } from '../../core/api/api.service';

/** `PatientModels.PatientRow`. Deliberately excludes every retention column. */
export interface ClinicPatient {
  readonly id?: string;
  readonly patientNo?: string;
  readonly displayName?: string;
  readonly givenName?: string;
  readonly familyName?: string;
  readonly gender?: string | null;
  readonly dateOfBirth?: string;
  readonly primaryPhone?: string;
  readonly primaryEmail?: string;
  readonly cnic?: string | null;
  readonly profileImageUrl?: string | null;
  readonly addressLine?: string | null;
  readonly country?: string | null;
  readonly state?: string | null;
  readonly city?: string | null;
  readonly postalCode?: string | null;
  readonly isActive?: boolean;
  readonly locale?: string;
  readonly createdAt?: string;
  /** An identifier linking to the CRM record â€” not a licence to read that record. */
  readonly customerId?: string;
  readonly deleted?: boolean;
}

/** `OdontogramModels.FindingRow`. Append-only: a correction is a new row. */
export interface ClinicFinding {
  readonly id?: string;
  readonly patientId?: string;
  readonly toothFdi?: number;
  readonly scope?: string;
  readonly surfaces?: readonly string[];
  readonly code?: string;
  readonly attributes?: string;
  readonly state?: string;
  readonly source?: string;
  readonly plannedProcedureId?: string;
  readonly recordedBy?: string;
  readonly recordedAt?: string;
  readonly note?: string;
  readonly supersededBy?: string;
}

/** Request payload to record a new odontogram finding (CLN-03, Slice S4). */
export interface RecordFindingPayload {
  readonly toothFdi: number;
  readonly scope: string;
  readonly surfaces?: readonly string[];
  readonly code: string;
  readonly attributesJson?: string;
  readonly state?: string;
  readonly source?: string;
  readonly plannedProcedureId?: string;
  readonly note?: string;
}

/** Request payload to correct an existing finding by superseding it. */
export interface CorrectFindingPayload {
  readonly code: string;
  readonly attributesJson?: string;
  readonly note?: string;
}

/** 6-site measurements for a single tooth during a perio exam. */
export interface PeriodontalMeasurementInput {
  readonly toothFdi: number;
  readonly probingDepthMb?: number | null;
  readonly probingDepthB?: number | null;
  readonly probingDepthDb?: number | null;
  readonly probingDepthMl?: number | null;
  readonly probingDepthL?: number | null;
  readonly probingDepthDl?: number | null;
  readonly gingivalMarginMb?: number | null;
  readonly gingivalMarginB?: number | null;
  readonly gingivalMarginDb?: number | null;
  readonly gingivalMarginMl?: number | null;
  readonly gingivalMarginL?: number | null;
  readonly gingivalMarginDl?: number | null;
  readonly bopMb?: boolean;
  readonly bopB?: boolean;
  readonly bopDb?: boolean;
  readonly bopMl?: boolean;
  readonly bopL?: boolean;
  readonly bopDl?: boolean;
  readonly suppurationMb?: boolean;
  readonly suppurationB?: boolean;
  readonly suppurationDb?: boolean;
  readonly suppurationMl?: boolean;
  readonly suppurationL?: boolean;
  readonly suppurationDl?: boolean;
  readonly plaqueMb?: boolean;
  readonly plaqueB?: boolean;
  readonly plaqueDb?: boolean;
  readonly plaqueMl?: boolean;
  readonly plaqueL?: boolean;
  readonly plaqueDl?: boolean;
  readonly furcationClass?: number | null;
  readonly mobilityGrade?: number | null;
}

/** Read model for a 6-site periodontal measurement with derived CAL. */
export interface PeriodontalMeasurement extends PeriodontalMeasurementInput {
  readonly id?: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly examId?: string;
  readonly patientId?: string;
  readonly calMb?: number | null;
  readonly calB?: number | null;
  readonly calDb?: number | null;
  readonly calMl?: number | null;
  readonly calL?: number | null;
  readonly calDl?: number | null;
  readonly createdAt?: string;
}

/** Header record for a periodontal examination session. */
export interface PeriodontalExamHeader {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId: string;
  readonly examinedByUserId?: string | null;
  readonly examinedByUserName?: string | null;
  readonly examinedAt: string;
  readonly notes?: string | null;
  readonly measurementCount: number;
  readonly createdAt: string;
}

/** Detail for a periodontal exam session with all tooth measurements. */
export interface PeriodontalExamDetail {
  readonly exam: PeriodontalExamHeader;
  readonly measurements: readonly PeriodontalMeasurement[];
}

/** Payload to create a new periodontal exam session. */
export interface CreatePeriodontalExamPayload {
  readonly examinedByUserId?: string;
  readonly examinedAt?: string;
  readonly notes?: string;
  readonly measurements: readonly PeriodontalMeasurementInput[];
}

/** `OdontogramModels.ChartEntry`: the finding, and whether it still stands. */
export interface ClinicChartEntry {
  readonly finding?: ClinicFinding;
  readonly superseded?: boolean;
}

/** `ScheduleModels.Slot`. `patientMinutes` and `blockedUntil` differ under DEC-001. */
export interface ClinicSlot {
  readonly startsAt?: string;
  readonly patientMinutes?: number;
  readonly blockedUntil?: string;
}

/** The query every route here accepts. `projectId` is a request, never a grant. */
export interface ClinicPatientQuery extends QueryParams {
  projectId?: string;
  q?: string;
}

@Injectable({ providedIn: 'root' })
export class ClinicPatientApi {
  private readonly api = inject(ApiService);

  /**
   * `GET /api/v1/clinic/patients`
   *
   * A bare call lists the clinic's patients; `q` searches name and phone. The server
   * states that the term is **never** matched against a date of birth, and the patients
   * screen says so on the screen rather than leaving a receptionist to wonder why a
   * birth date finds nothing.
   *
   * A foreign `projectId` and a nonexistent one are the same non-disclosing 404.
   */
  patients(query: ClinicPatientQuery = {}): Promise<ClinicPatient[]> {
    return this.api.get('/api/v1/clinic/patients', query);
  }

  /** `GET /api/v1/clinic/patients/{patientId}` â€” absent and unauthorized are one answer. */
  patient(patientId: string, query: ClinicPatientQuery = {}): Promise<ClinicPatient> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}`,
      query,
    );
  }

  /** `GET /api/v1/clinic/patients/{patientId}/odontogram` â€” newest observation first. */
  odontogram(
    patientId: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicChartEntry[]> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/odontogram`,
      query,
    );
  }

  /**
   * `GET /api/v1/clinic/patients/{patientId}/odontogram/tooth/{toothFdi}`
   *
   * One tooth's history, oldest first. A number the server does not recognise matches no
   * rows rather than being refused â€” which is why this screen only ever requests a tooth
   * number the server itself published on a finding.
   */
  toothHistory(
    patientId: string,
    toothFdi: number,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicFinding[]> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/odontogram/tooth/${encodeURIComponent(
        String(toothFdi),
      )}`,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/odontogram/findings` â€” record a finding. */
  recordFinding(
    patientId: string,
    payload: RecordFindingPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicFinding> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/odontogram/findings`,
      payload,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/odontogram/findings/{findingId}/correct` â€” supersede finding. */
  correctFinding(
    patientId: string,
    findingId: string,
    payload: CorrectFindingPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicFinding> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/odontogram/findings/${encodeURIComponent(findingId)}/correct`,
      payload,
      query,
    );
  }

  /** `GET /api/v1/clinic/patients/{patientId}/periodontal/exams` â€” list periodontal exams. */
  periodontalExams(
    patientId: string,
    query: ClinicPatientQuery = {},
  ): Promise<PeriodontalExamHeader[]> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/periodontal/exams`,
      query,
    );
  }

  /** `GET /api/v1/clinic/patients/{patientId}/periodontal/exams/{examId}` â€” periodontal exam detail. */
  periodontalExam(
    patientId: string,
    examId: string,
    query: ClinicPatientQuery = {},
  ): Promise<PeriodontalExamDetail> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/periodontal/exams/${encodeURIComponent(examId)}`,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/periodontal/exams` â€” record periodontal exam session. */
  createPeriodontalExam(
    patientId: string,
    payload: CreatePeriodontalExamPayload,
    query: ClinicPatientQuery = {},
  ): Promise<PeriodontalExamDetail> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/periodontal/exams`,
      payload,
      query,
    );
  }

  /** `GET /api/v1/clinic/patients/{patientId}/periodontal/latest` â€” latest measurements per tooth. */
  latestPeriodontalChart(
    patientId: string,
    query: ClinicPatientQuery = {},
  ): Promise<PeriodontalMeasurement[]> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/periodontal/latest`,
      query,
    );
  }

  /**
   * `GET /api/v1/clinic/availability` â€” bookable slots for one dentist on one date.
   *
   * `doctorId` and `date` are required. `durationMinutes` and `bufferMinutes` are
   * sent because the selected service owns them: the server's 30/0 defaults are the
   * fallback, not the clinic's price list, and silently querying a 30-minute grid for
   * a 60-minute service would offer a slot the appointment cannot occupy.
   */
  availability(
    query: ClinicPatientQuery & {
      doctorId: string;
      date: string;
      durationMinutes?: number;
      bufferMinutes?: number;
    },
  ): Promise<ClinicSlot[]> {
    return this.api.get('/api/v1/clinic/availability', query);
  }

  // ---- Treatment Plans (TRT-01 to TRT-05) -----------------------------------

  /** `GET /api/v1/clinic/patients/{patientId}/treatment-plans` â€” list patient plans with financial totals. */
  treatmentPlans(patientId: string, query: ClinicPatientQuery = {}): Promise<ClinicTreatmentPlanHeader[]> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans`, query);
  }

  /** `GET /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}` â€” plan detail with ordered items. */
  treatmentPlan(patientId: string, planId: string, query: ClinicPatientQuery = {}): Promise<ClinicTreatmentPlanDetail> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}`,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/treatment-plans` â€” create a plan in DRAFT status. */
  createTreatmentPlan(
    patientId: string,
    payload: CreateTreatmentPlanPayload,
  ): Promise<{ id: string; status: string }> {
    return this.api.post(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans`, payload);
  }

  /** `POST /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}/fork` â€” fork new version (Slice S5). */
  forkTreatmentPlan(
    patientId: string,
    planId: string,
    payload: ForkTreatmentPlanPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; status: string; parentPlanId: string }> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}/fork`,
      payload,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}/approve` â€” approve and activate plan with consent. */
  approveTreatmentPlan(
    patientId: string,
    planId: string,
    payload: ApproveTreatmentPlanPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ planId: string; status: string; approved: boolean }> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}/approve`,
      payload,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}/cancel` â€” cancel plan with reason. */
  cancelTreatmentPlan(
    patientId: string,
    planId: string,
    payload: CancelTreatmentPlanPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ planId: string; status: string }> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}/cancel`,
      payload,
      query,
    );
  }

  /** `PATCH /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}/status` â€” change plan status. */
  updateTreatmentPlanStatus(
    patientId: string,
    planId: string,
    payload: UpdateTreatmentPlanStatusPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ planId: string; status: string }> {
    return this.api.patch(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}/status`,
      payload,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}/consent` â€” record patient consent (TRT-04). */
  recordTreatmentPlanConsent(
    patientId: string,
    planId: string,
    payload: RecordTreatmentPlanConsentPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ planId: string; patientConsent: boolean }> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}/consent`,
      payload,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}/items` â€” add procedure step. */
  addTreatmentPlanItem(
    patientId: string,
    planId: string,
    payload: CreateTreatmentPlanItemPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; planId: string }> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}/items`,
      payload,
      query,
    );
  }

  /** `PATCH /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}/items/{itemId}` â€” update item. */
  updateTreatmentPlanItem(
    patientId: string,
    planId: string,
    itemId: string,
    payload: UpdateTreatmentPlanItemPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; updated: boolean }> {
    return this.api.patch(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}/items/${encodeURIComponent(itemId)}`,
      payload,
      query,
    );
  }

  /** `DELETE /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}/items/{itemId}` â€” remove item. */
  deleteTreatmentPlanItem(
    patientId: string,
    planId: string,
    itemId: string,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; deleted: boolean }> {
    return this.api.delete(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}/items/${encodeURIComponent(itemId)}`,
      query,
    );
  }

  /** `DELETE /api/v1/clinic/patients/{patientId}/treatment-plans/{planId}` â€” remove plan. */
  deleteTreatmentPlan(
    patientId: string,
    planId: string,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; deleted: boolean }> {
    return this.api.delete(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/treatment-plans/${encodeURIComponent(planId)}`,
      query,
    );
  }

  /** `GET /api/v1/clinic/patients/{patientId}/encounters` â€” list patient encounters (CLN-01). */
  listEncounters(
    patientId: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicEncounterHeader[]> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/encounters`, query);
  }

  /** `GET /api/v1/clinic/patients/{patientId}/encounters/{encounterId}` â€” encounter detail with prescriptions. */
  getEncounterDetail(
    patientId: string,
    encounterId: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicEncounterDetail> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/encounters/${encodeURIComponent(encounterId)}`,
      query,
    );
  }

  /** `GET /api/v1/clinic/encounters/{encounterId}` â€” direct encounter lookup. */
  getEncounterById(
    encounterId: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicEncounterDetail> {
    return this.api.get(`/api/v1/clinic/encounters/${encodeURIComponent(encounterId)}`, query);
  }

  /** `POST /api/v1/clinic/patients/{patientId}/encounters` â€” record a new visit (CLN-01 to CLN-05). */
  createEncounter(
    patientId: string,
    payload: CreateEncounterPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; created: boolean; inventoryWarnings?: InventoryStockWarning[] }> {
    return this.api.post(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/encounters`, payload, query);
  }

  /** `PATCH /api/v1/clinic/patients/{patientId}/encounters/{encounterId}/diagnosis` â€” update diagnosis and findings (CLN-02). */
  updateDiagnosis(
    patientId: string,
    encounterId: string,
    payload: UpdateDiagnosisPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; updated: boolean }> {
    return this.api.patch(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/encounters/${encodeURIComponent(encounterId)}/diagnosis`,
      payload,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/encounters/{encounterId}/prescriptions` â€” issue prescription (CLN-06). */
  addPrescription(
    patientId: string,
    encounterId: string,
    payload: CreatePrescriptionPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; created: boolean }> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/encounters/${encodeURIComponent(encounterId)}/prescriptions`,
      payload,
      query,
    );
  }

  /** `DELETE /api/v1/clinic/patients/{patientId}/encounters/{encounterId}/prescriptions/{prescriptionId}`. */
  deletePrescription(
    patientId: string,
    encounterId: string,
    prescriptionId: string,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; deleted: boolean }> {
    return this.api.delete(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/encounters/${encodeURIComponent(encounterId)}/prescriptions/${encodeURIComponent(prescriptionId)}`,
      query,
    );
  }

  /** `GET /api/v1/clinic/prescriptions` â€” list all grouped clinic prescription sheets. */
  listClinicPrescriptions(query: ClinicPatientQuery = {}): Promise<ClinicPrescriptionSheet[]> {
    return this.api.get('/api/v1/clinic/prescriptions', query);
  }

  /** `GET /api/v1/clinic/prescriptions/{id}` â€” get a single prescription sheet by ID. */
  getClinicPrescription(
    id: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicPrescriptionSheet> {
    return this.api.get(`/api/v1/clinic/prescriptions/${encodeURIComponent(id)}`, query);
  }

  /** `POST /api/v1/clinic/prescriptions` â€” create a standalone multi-item prescription. */
  createStandalonePrescription(
    payload: CreateStandalonePrescriptionPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicPrescriptionSheet> {
    return this.api.post('/api/v1/clinic/prescriptions', payload, query);
  }

  /** `DELETE /api/v1/clinic/prescriptions/{id}` â€” delete a prescription sheet. */
  deletePrescriptionSheet(
    id: string,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; deleted: boolean }> {
    return this.api.delete(`/api/v1/clinic/prescriptions/${encodeURIComponent(id)}`, query);
  }

  /** `GET /api/v1/clinic/settings/letterhead` â€” get customizable clinic letterhead settings. */
  getLetterheadSettings(query: ClinicPatientQuery = {}): Promise<ClinicLetterheadSettings> {
    return this.api.get('/api/v1/clinic/settings/letterhead', query);
  }

  /** `PUT /api/v1/clinic/settings/letterhead` â€” update customizable clinic letterhead settings. */
  updateLetterheadSettings(
    payload: UpdateLetterheadPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicLetterheadSettings> {
    return this.api.put('/api/v1/clinic/settings/letterhead', payload, query);
  }

  /** `GET /api/v1/clinic/settings/medications` â€” list saved medication presets. */
  listMedicationPresets(query: ClinicPatientQuery = {}): Promise<ClinicMedicationPreset[]> {
    return this.api.get('/api/v1/clinic/settings/medications', query);
  }

  /** `POST /api/v1/clinic/settings/medications` â€” create a medication preset. */
  createMedicationPreset(
    payload: CreateMedicationPresetPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicMedicationPreset> {
    return this.api.post('/api/v1/clinic/settings/medications', payload, query);
  }

  /** `DELETE /api/v1/clinic/settings/medications/{id}` â€” delete a medication preset. */
  deleteMedicationPreset(
    id: string,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; deleted: boolean }> {
    return this.api.delete(`/api/v1/clinic/settings/medications/${encodeURIComponent(id)}`, query);
  }

  /** `GET /api/v1/clinic/settings/diagnoses` â€” list quick diagnosis shortcuts. */
  listDiagnosisPresets(query: ClinicPatientQuery = {}): Promise<ClinicDiagnosisPreset[]> {
    return this.api.get('/api/v1/clinic/settings/diagnoses', query);
  }

  /** `POST /api/v1/clinic/settings/diagnoses` â€” create a diagnosis shortcut. */
  createDiagnosisPreset(
    payload: CreateDiagnosisPresetPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicDiagnosisPreset> {
    return this.api.post('/api/v1/clinic/settings/diagnoses', payload, query);
  }

  /** `DELETE /api/v1/clinic/settings/diagnoses/{id}` â€” delete a diagnosis shortcut. */
  deleteDiagnosisPreset(
    id: string,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; deleted: boolean }> {
    return this.api.delete(`/api/v1/clinic/settings/diagnoses/${encodeURIComponent(id)}`, query);
  }

  /** `POST /api/v1/clinic/encounters/{encounterId}/sign` â€” digitally sign an encounter note (Slice S3). */
  signEncounter(
    encounterId: string,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; signed: boolean; signatureHash: string }> {
    return this.api.post(`/api/v1/clinic/encounters/${encodeURIComponent(encounterId)}/sign`, {}, query);
  }

  /** `POST /api/v1/clinic/encounters/{encounterId}/amendments` â€” append immutable amendment to signed note (Slice S3). */
  addAmendment(
    encounterId: string,
    payload: CreateAmendmentPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; encounterId: string; created: boolean }> {
    return this.api.post(`/api/v1/clinic/encounters/${encodeURIComponent(encounterId)}/amendments`, payload, query);
  }

  /** `GET /api/v1/clinic/patients/{patientId}/medical-history` â€” fetch patient medical history and alerts (Slice S3). */
  medicalHistory(
    patientId: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicMedicalHistory | null> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/medical-history`, query);
  }

  /** `PUT /api/v1/clinic/patients/{patientId}/medical-history` â€” save or update patient medical history (Slice S3). */
  saveMedicalHistory(
    patientId: string,
    payload: SaveMedicalHistoryPayload,
    query: ClinicPatientQuery = {},
  ): Promise<{ id: string; patientId: string; saved: boolean }> {
    return this.api.put(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/medical-history`, payload, query);
  }

  // ---- Invoices & Payments (BIL-01 to BIL-09) -------------------------------

  /** `GET /api/v1/clinic/invoices` â€” list clinic invoices with optional status filter. */
  invoices(query: ClinicPatientQuery & { status?: string } = {}): Promise<ClinicInvoiceHeader[]> {
    return this.api.get('/api/v1/clinic/invoices', query);
  }

  /** `GET /api/v1/clinic/invoices/{invoiceId}` â€” get full invoice detail with items & payments. */
  invoice(invoiceId: string, query: ClinicPatientQuery = {}): Promise<ClinicInvoiceDetail> {
    return this.api.get(`/api/v1/clinic/invoices/${encodeURIComponent(invoiceId)}`, query);
  }

  /** `GET /api/v1/clinic/patients/{patientId}/invoices` â€” list all invoices for a patient. */
  patientInvoices(patientId: string, query: ClinicPatientQuery = {}): Promise<ClinicInvoiceHeader[]> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/invoices`, query);
  }

  /** `POST /api/v1/clinic/invoices` â€” issue a new invoice with line items (BIL-01). */
  createInvoice(payload: CreateInvoicePayload, query: ClinicPatientQuery = {}): Promise<ClinicInvoiceHeader> {
    return this.api.post('/api/v1/clinic/invoices', payload, query);
  }

  /** `POST /api/v1/clinic/invoices/{invoiceId}/discount` â€” apply a discount with mandatory reason (BIL-04). */
  applyDiscount(
    invoiceId: string,
    payload: ApplyDiscountPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicInvoiceHeader> {
    return this.api.post(`/api/v1/clinic/invoices/${encodeURIComponent(invoiceId)}/discount`, payload, query);
  }

  /** `POST /api/v1/clinic/invoices/{invoiceId}/cancel` â€” cancel an invoice with mandatory reason (BIL-05). */
  cancelInvoice(
    invoiceId: string,
    payload: CancelInvoicePayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicInvoiceHeader> {
    return this.api.post(`/api/v1/clinic/invoices/${encodeURIComponent(invoiceId)}/cancel`, payload, query);
  }

  /** `GET /api/v1/clinic/payments` â€” list all payments across clinic (BIL-02). */
  payments(query: ClinicPatientQuery = {}): Promise<ClinicPaymentRow[]> {
    return this.api.get('/api/v1/clinic/payments', query);
  }

  /** `POST /api/v1/clinic/invoices/{invoiceId}/payments` â€” record a payment and receive receipt (BIL-02, BIL-06). */
  recordPayment(
    invoiceId: string,
    payload: RecordPaymentPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicPaymentReceipt> {
    return this.api.post(`/api/v1/clinic/invoices/${encodeURIComponent(invoiceId)}/payments`, payload, query);
  }

  /** `GET /api/v1/clinic/payments/{paymentId}/receipt` â€” fetch payment receipt for printing/PDF (BIL-06). */
  paymentReceipt(paymentId: string, query: ClinicPatientQuery = {}): Promise<ClinicPaymentReceipt> {
    return this.api.get(`/api/v1/clinic/payments/${encodeURIComponent(paymentId)}/receipt`, query);
  }

  /** `POST /api/v1/clinic/invoices/from-plan` â€” generate invoice from approved treatment plan items. */
  generateInvoiceFromPlan(
    payload: GenerateInvoiceFromPlanPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicInvoiceHeader> {
    return this.api.post('/api/v1/clinic/invoices/from-plan', payload, query);
  }

  /** `POST /api/v1/clinic/invoices/{invoiceId}/refunds` â€” issue refund or payment reversal. */
  recordRefund(
    invoiceId: string,
    payload: RecordRefundPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicRefundRow> {
    return this.api.post(`/api/v1/clinic/invoices/${encodeURIComponent(invoiceId)}/refunds`, payload, query);
  }

  /** `GET /api/v1/clinic/invoices/{invoiceId}/refunds` â€” list refunds for an invoice. */
  invoiceRefunds(invoiceId: string, query: ClinicPatientQuery = {}): Promise<ClinicRefundRow[]> {
    return this.api.get(`/api/v1/clinic/invoices/${encodeURIComponent(invoiceId)}/refunds`, query);
  }

  /** `GET /api/v1/clinic/patients/{patientId}/ledger` â€” get append-only chronological ledger entries. */
  patientLedger(patientId: string, query: ClinicPatientQuery = {}): Promise<PatientLedgerEntryRow[]> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/ledger`, query);
  }

  /** `GET /api/v1/clinic/patients/{patientId}/statement` â€” consolidated statement of account. */
  patientStatement(patientId: string, query: ClinicPatientQuery = {}): Promise<PatientStatement> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/statement`, query);
  }

  // ---- Reports & Policies (RPT-01 to RPT-05, USR-09) -----------------------

  /** `GET /api/v1/clinic/reports` â€” aggregated clinical & financial metrics. */
  reports(
    query: ClinicPatientQuery & { window?: string; start?: string; end?: string } = {},
  ): Promise<ClinicReportView> {
    return this.api.get('/api/v1/clinic/reports', query);
  }

  /** `GET /api/v1/clinic/settings/policies` â€” read clinic policies (USR-09, BIL-07). */
  policies(query: ClinicPatientQuery = {}): Promise<ClinicPolicyRow> {
    return this.api.get('/api/v1/clinic/settings/policies', query);
  }

  /** `PUT /api/v1/clinic/settings/policies` â€” update clinic policies. */
  updatePolicies(payload: UpdatePolicyPayload, query: ClinicPatientQuery = {}): Promise<ClinicPolicyRow> {
    return this.api.put('/api/v1/clinic/settings/policies', payload, query);
  }

  // ---- AI Assistant & Patient Management (CLN-09, PAT-04) ------------------

  /** `GET /api/v1/clinic/ai/runs` â€” list operational AI runs for clinic. */
  aiRuns(query: ClinicPatientQuery & { status?: string; limit?: number } = {}): Promise<ClinicAiRun[]> {
    return this.api.get('/api/v1/clinic/ai/runs', query);
  }

  /** `GET /api/v1/clinic/ai/overview` â€” get AI assistant metrics and CLN-09 compliance confirmation. */
  aiOverview(query: ClinicPatientQuery = {}): Promise<ClinicAiOverview> {
    return this.api.get('/api/v1/clinic/ai/overview', query);
  }

  /** `GET /api/v1/clinic/ai/governance` â€” get AI assistant governance and emergency kill switch status. */
  aiGovernance(query: ClinicPatientQuery = {}): Promise<ClinicAiGovernanceRow> {
    return this.api.get('/api/v1/clinic/ai/governance', query);
  }

  /** `POST /api/v1/clinic/ai/governance/kill-switch` â€” toggle emergency kill switch. */
  toggleAiKillSwitch(payload: ToggleKillSwitchPayload, query: ClinicPatientQuery = {}): Promise<ClinicAiGovernanceRow> {
    return this.api.post('/api/v1/clinic/ai/governance/kill-switch', payload, query);
  }

  /** `GET /api/v1/clinic/ai/agent-config` â€” get receptionist agent profile & channels */
  getAgentConfig(query: ClinicPatientQuery = {}): Promise<ClinicAgentConfig> {
    return this.api.get('/api/v1/clinic/ai/agent-config', query);
  }

  /** `PUT /api/v1/clinic/ai/agent-config` â€” update receptionist agent profile */
  updateAgentConfig(config: ClinicAgentConfig, query: ClinicPatientQuery = {}): Promise<ClinicAgentConfig> {
    return this.api.put('/api/v1/clinic/ai/agent-config', config, query);
  }

  /** `GET /api/v1/clinic/ai/agent-metrics` â€” get receptionist agent KPIs */
  getAgentMetrics(query: ClinicPatientQuery = {}): Promise<ClinicAgentMetrics> {
    return this.api.get('/api/v1/clinic/ai/agent-metrics', query);
  }

  /** `POST /api/v1/clinic/ai/test-chat` â€” test conversation with receptionist agent */
  testAgentChat(message: string, query: ClinicPatientQuery = {}): Promise<ClinicTestChatResponse> {
    return this.api.post('/api/v1/clinic/ai/test-chat', { message }, query);
  }

  /** `GET /api/v1/clinic/ai/proposals` â€” list governed AI proposals. */
  aiProposals(
    query: ClinicPatientQuery & {
      patientId?: string;
      proposalType?: string;
      status?: string;
      limit?: number;
    } = {},
  ): Promise<ClinicAiProposalRow[]> {
    return this.api.get('/api/v1/clinic/ai/proposals', query);
  }

  /** `POST /api/v1/clinic/ai/proposals/{proposalId}/review` â€” human review of AI proposal. */
  reviewAiProposal(
    proposalId: string,
    payload: ReviewProposalPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicAiProposalRow> {
    return this.api.post(`/api/v1/clinic/ai/proposals/${encodeURIComponent(proposalId)}/review`, payload, query);
  }

  /** `POST /api/v1/clinic/ai/proposals/visit-summary` â€” generate clinical visit summary draft. */
  generateVisitSummaryDraft(payload: VisitSummaryDraftPayload, query: ClinicPatientQuery = {}): Promise<ClinicAiProposalRow> {
    return this.api.post('/api/v1/clinic/ai/proposals/visit-summary', payload, query);
  }

  /** `POST /api/v1/clinic/ai/proposals/recall-prioritization` â€” generate recall/no-show prioritization proposal. */
  generateRecallPrioritization(payload: RecallPrioritizationPayload, query: ClinicPatientQuery = {}): Promise<ClinicAiProposalRow> {
    return this.api.post('/api/v1/clinic/ai/proposals/recall-prioritization', payload, query);
  }

  /** `POST /api/v1/clinic/ai/proposals/missing-fields` â€” audit missing fields in chart. */
  auditMissingFields(payload: MissingFieldsAuditPayload, query: ClinicPatientQuery = {}): Promise<ClinicAiProposalRow> {
    return this.api.post('/api/v1/clinic/ai/proposals/missing-fields', payload, query);
  }

  /** `POST /api/v1/clinic/ai/proposals/payment-matching` â€” propose FIFO payment matching. */
  proposePaymentMatching(payload: PaymentMatchProposalPayload, query: ClinicPatientQuery = {}): Promise<ClinicAiProposalRow> {
    return this.api.post('/api/v1/clinic/ai/proposals/payment-matching', payload, query);
  }

  /** `POST /api/v1/clinic/ai/proposals/patient-message` â€” draft patient communication message. */
  draftPatientMessage(payload: PatientMessageDraftPayload, query: ClinicPatientQuery = {}): Promise<ClinicAiProposalRow> {
    return this.api.post('/api/v1/clinic/ai/proposals/patient-message', payload, query);
  }

  /** `GET /api/v1/clinic/patients/duplicates` â€” check potential duplicate records (PAT-04). */
  duplicateCandidates(
    params: {
      givenName?: string;
      familyName?: string;
      dateOfBirth?: string;
      phone?: string;
      excludePatientId?: string;
      projectId?: string;
    } = {},
  ): Promise<ClinicDuplicateCandidate[]> {
    return this.api.get('/api/v1/clinic/patients/duplicates', params as unknown as QueryParams);
  }

  /** `POST /api/v1/clinic/patients` â€” register a new patient in the clinic. */
  createPatient(payload: CreatePatientPayload, query: ClinicPatientQuery = {}): Promise<ClinicPatient> {
    return this.api.post('/api/v1/clinic/patients', payload, query);
  }

  // ---- S1: Relationships, Consents, Disclosures, Record Actions -----------

  relationships(patientId: string, query: ClinicPatientQuery = {}): Promise<ClinicRelationship[]> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/relationships`, query);
  }

  addRelationship(patientId: string, payload: CreateRelationshipPayload, query: ClinicPatientQuery = {}): Promise<string> {
    return this.api.post(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/relationships`, payload, query);
  }

  deleteRelationship(patientId: string, relationshipId: string, query: ClinicPatientQuery = {}): Promise<void> {
    return this.api.delete(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/relationships/${encodeURIComponent(relationshipId)}`, query);
  }

  consents(patientId: string, query: ClinicPatientQuery = {}): Promise<ClinicConsent[]> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/consents`, query);
  }

  addConsent(patientId: string, payload: CreateConsentPayload, query: ClinicPatientQuery = {}): Promise<string> {
    return this.api.post(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/consents`, payload, query);
  }

  revokeConsent(patientId: string, consentId: string, reason: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.post(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/consents/${encodeURIComponent(consentId)}/revoke`, { revocationReason: reason }, query);
  }

  disclosures(patientId: string, query: ClinicPatientQuery = {}): Promise<ClinicDisclosure[]> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/disclosures`, query);
  }

  logDisclosure(patientId: string, payload: LogDisclosurePayload, query: ClinicPatientQuery = {}): Promise<string> {
    return this.api.post(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/disclosures`, payload, query);
  }

  archivePatient(patientId: string, reason: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.post(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/archive`, { reason }, query);
  }

  restorePatient(patientId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.post(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/restore`, {}, query);
  }

  mergePatients(payload: MergePatientsPayload, query: ClinicPatientQuery = {}): Promise<MergePatientsResult> {
    return this.api.post('/api/v1/clinic/patients/merge', payload, query);
  }

  // ---- Scheduling Hardening, Resources, Holds, Waitlist, Reminders (Slice S2) ----

  resources(query: { projectId?: string; limit?: number } = {}): Promise<ClinicResourceDetail[]> {
    return this.api.get('/api/v1/clinic/schedules/resources', query);
  }

  createResource(payload: { name: string; capabilities?: string[] }, query: { projectId?: string } = {}): Promise<{ id: string; name: string }> {
    return this.api.post('/api/v1/clinic/schedules/resources', payload, query);
  }

  addResourceCapability(resourceId: string, payload: { capability: string }, query: { projectId?: string } = {}): Promise<{ id: string; capability: string }> {
    return this.api.post(`/api/v1/clinic/schedules/resources/${encodeURIComponent(resourceId)}/capabilities`, payload, query);
  }

  assignResource(appointmentId: string, payload: { resourceId?: string | null; bufferMinutes?: number }, query: { projectId?: string } = {}): Promise<{ updated: number }> {
    return this.api.post(`/api/v1/clinic/schedules/appointments/${encodeURIComponent(appointmentId)}/assign-resource`, payload, query);
  }

  createAppointment(
    payload: CreateAppointmentPayload,
    query: { projectId?: string } = {},
  ): Promise<{ id: string; ticketNumber: string; status: string; appointmentDate: string }> {
    return this.api.post('/api/v1/clinic/schedules/appointments', payload, query);
  }

  updateAppointmentStatus(
    appointmentId: string,
    payload: UpdateAppointmentStatusPayload,
    query: { projectId?: string } = {},
  ): Promise<{ id: string; status: string; updated: boolean; inventoryWarnings?: InventoryStockWarning[] }> {
    return this.api.patch(`/api/v1/clinic/schedules/appointments/${encodeURIComponent(appointmentId)}/status`, payload, query);
  }

  assignTreatmentRoom(
    appointmentId: string,
    treatmentRoomId: string | null,
    query: { projectId?: string } = {},
  ): Promise<{ id: string; treatmentRoomId: string; updated: boolean }> {
    return this.api.patch(
      `/api/v1/clinic/schedules/appointments/${encodeURIComponent(appointmentId)}/assign-room`,
      { treatmentRoomId },
      query,
    );
  }

  /** `PATCH /api/v1/clinic/schedules/appointments/{id}/payment` â€” a separate decision from status. */
  updateAppointmentPayment(
    appointmentId: string,
    paymentStatus: string,
    query: { projectId?: string } = {},
  ): Promise<{ id: string; paymentStatus: string; updated: boolean }> {
    return this.api.patch(
      `/api/v1/clinic/schedules/appointments/${encodeURIComponent(appointmentId)}/payment`,
      { paymentStatus },
      query,
    );
  }

  holds(query: { projectId?: string; doctorId?: string; date?: string } = {}): Promise<ClinicHoldRow[]> {
    return this.api.get('/api/v1/clinic/schedules/holds', query);
  }

  createHold(payload: CreateHoldPayload, query: { projectId?: string } = {}): Promise<{ id: string; status: string }> {
    return this.api.post('/api/v1/clinic/schedules/holds', payload, query);
  }

  releaseHold(holdId: string, query: { projectId?: string } = {}): Promise<{ id: string; released: boolean }> {
    return this.api.delete(`/api/v1/clinic/schedules/holds/${encodeURIComponent(holdId)}`, query);
  }

  waitlist(query: { projectId?: string; status?: string; limit?: number } = {}): Promise<ClinicWaitlistRow[]> {
    return this.api.get('/api/v1/clinic/schedules/waitlist', query);
  }

  createWaitlist(payload: CreateWaitlistPayload, query: { projectId?: string } = {}): Promise<{ id: string; status: string }> {
    return this.api.post('/api/v1/clinic/schedules/waitlist', payload, query);
  }

  updateWaitlist(waitlistId: string, payload: UpdateWaitlistPayload, query: { projectId?: string } = {}): Promise<{ id: string; updated: boolean }> {
    return this.api.patch(`/api/v1/clinic/schedules/waitlist/${encodeURIComponent(waitlistId)}`, payload, query);
  }

  reminders(query: { projectId?: string; status?: string; limit?: number } = {}): Promise<ClinicReminderRow[]> {
    return this.api.get('/api/v1/clinic/schedules/reminders', query);
  }

  createReminder(payload: CreateReminderPayload, query: { projectId?: string } = {}): Promise<{ id: string; status: string }> {
    return this.api.post('/api/v1/clinic/schedules/reminders', payload, query);
  }

  updateReminder(reminderId: string, payload: { status: string; failureReason?: string }, query: { projectId?: string } = {}): Promise<{ id: string; updated: boolean }> {
    return this.api.patch(`/api/v1/clinic/schedules/reminders/${encodeURIComponent(reminderId)}`, payload, query);
  }

  // ---- Clinical Media, Radiographs & Documents (Slice S7) ------------------

  /** `GET /api/v1/clinic/patients/{patientId}/media` â€” list clinical radiographs, photos, and documents. */
  patientMedia(
    patientId: string,
    query: ClinicPatientQuery & { category?: string; toothFdi?: number; legalHoldOnly?: boolean } = {},
  ): Promise<ClinicMediaRow[]> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/media`, query);
  }

  /** `POST /api/v1/clinic/patients/{patientId}/media` â€” upload and encrypt clinical media. */
  uploadMedia(
    patientId: string,
    payload: UploadMediaPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicMediaRow> {
    const formData = new FormData();
    formData.append('file', payload.file);
    formData.append('category', payload.category);
    if (payload.toothFdi !== undefined && payload.toothFdi !== null) {
      formData.append('toothFdi', String(payload.toothFdi));
    }
    if (payload.acquisitionDate) {
      formData.append('acquisitionDate', payload.acquisitionDate);
    }
    if (payload.radiographerName) {
      formData.append('radiographerName', payload.radiographerName);
    }
    if (payload.notes) {
      formData.append('notes', payload.notes);
    }
    if (payload.retentionClass) {
      formData.append('retentionClass', payload.retentionClass);
    }
    return this.api.post(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/media`, formData, query);
  }

  /** `GET /api/v1/clinic/patients/{patientId}/media/{mediaId}/download-url` â€” mint short-lived signed URL. */
  mediaDownloadUrl(
    patientId: string,
    mediaId: string,
    query: ClinicPatientQuery & { ttlMinutes?: number } = {},
  ): Promise<ClinicSignedDownloadUrl> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/media/${encodeURIComponent(mediaId)}/download-url`,
      query,
    );
  }

  /** `POST /api/v1/clinic/patients/{patientId}/media/{mediaId}/legal-hold` â€” toggle legal hold (DEC-007). */
  toggleLegalHold(
    patientId: string,
    mediaId: string,
    legalHold: boolean,
    reason: string,
    query: ClinicPatientQuery = {},
  ): Promise<void> {
    return this.api.post(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/media/${encodeURIComponent(mediaId)}/legal-hold`,
      { legalHold, reason },
      query,
    );
  }

  /** `DELETE /api/v1/clinic/patients/{patientId}/media/{mediaId}` â€” soft-delete media (refused if hold active). */
  deleteMedia(
    patientId: string,
    mediaId: string,
    reason: string = 'User requested',
    query: ClinicPatientQuery = {},
  ): Promise<void> {
    return this.api.delete(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/media/${encodeURIComponent(mediaId)}`,
      { ...query, reason },
    );
  }

  /** `GET /api/v1/clinic/patients/{patientId}/media/{mediaId}/audit` â€” get media access audit trail. */
  mediaAudit(
    patientId: string,
    mediaId: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicMediaAccessAuditRow[]> {
    return this.api.get(
      `/api/v1/clinic/patients/${encodeURIComponent(patientId)}/media/${encodeURIComponent(mediaId)}/audit`,
      query,
    );
  }

  /** `GET /api/v1/clinic/patients/{patientId}/media-audit` â€” get audit trail for all patient media. */
  patientMediaAudit(
    patientId: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicMediaAccessAuditRow[]> {
    return this.api.get(`/api/v1/clinic/patients/${encodeURIComponent(patientId)}/media-audit`, query);
  }

  // ---- Slice S8: Break-Glass Emergency Access, Audit Stream, and Curated Reports ----

  requestBreakGlass(
    payload: CreateBreakGlassRequest,
    query: ClinicPatientQuery = {},
  ): Promise<BreakGlassGrantRow> {
    return this.api.post('/api/v1/clinic/break-glass', payload, query);
  }

  activeBreakGlass(
    query: ClinicPatientQuery = {},
  ): Promise<BreakGlassGrantRow[]> {
    return this.api.get('/api/v1/clinic/break-glass/active', query);
  }

  pendingReviewBreakGlass(
    query: ClinicPatientQuery = {},
  ): Promise<BreakGlassGrantRow[]> {
    return this.api.get('/api/v1/clinic/break-glass/pending-review', query);
  }

  revokeBreakGlass(
    grantId: string,
    payload: RevokeBreakGlassRequest = {},
    query: ClinicPatientQuery = {},
  ): Promise<{ revoked: boolean; grantId: string }> {
    return this.api.post(
      `/api/v1/clinic/break-glass/${encodeURIComponent(grantId)}/revoke`,
      payload,
      query,
    );
  }

  reviewBreakGlass(
    grantId: string,
    payload: ReviewBreakGlassRequest,
    query: ClinicPatientQuery = {},
  ): Promise<{ reviewed: boolean; grantId: string }> {
    return this.api.post(
      `/api/v1/clinic/break-glass/${encodeURIComponent(grantId)}/review`,
      payload,
      query,
    );
  }

  clinicAuditLogs(
    filters: {
      patientId?: string;
      actorUserId?: string;
      action?: string;
      resourceType?: string;
      from?: string;
      to?: string;
      limit?: number;
      projectId?: string;
    } = {},
  ): Promise<ClinicAuditRow[]> {
    return this.api.get('/api/v1/clinic/audit', filters);
  }

  verifyAuditChain(
    query: ClinicPatientQuery = {},
  ): Promise<{ integrityVerified: boolean; algorithm: string }> {
    return this.api.get('/api/v1/clinic/audit/verify', query);
  }

  appointmentProductivity(
    from?: string,
    to?: string,
    doctorId?: string,
    query: ClinicPatientQuery = {},
  ): Promise<AppointmentProductivityRow[]> {
    const params: QueryParams = {
      ...query,
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
      ...(doctorId ? { doctorId } : {}),
    };
    return this.api.get('/api/v1/clinic/reports/appointment-productivity', params);
  }

  procedureUtilization(
    from?: string,
    to?: string,
    query: ClinicPatientQuery = {},
  ): Promise<ProcedureUtilizationRow[]> {
    const params: QueryParams = {
      ...query,
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
    };
    return this.api.get('/api/v1/clinic/reports/procedure-utilization', params);
  }

  financialCollection(
    from?: string,
    to?: string,
    query: ClinicPatientQuery = {},
  ): Promise<FinancialCollectionSummaryRow[]> {
    const params: QueryParams = {
      ...query,
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
    };
    return this.api.get('/api/v1/clinic/reports/financial-collection', params);
  }

  patientRecall(
    overdueDaysThreshold = 180,
    query: ClinicPatientQuery = {},
  ): Promise<PatientRetentionRecallRow[]> {
    return this.api.get('/api/v1/clinic/reports/patient-recall', {
      ...query,
      overdueDaysThreshold,
    });
  }

  breakGlassCompliance(
    from?: string,
    to?: string,
    query: ClinicPatientQuery = {},
  ): Promise<BreakGlassComplianceRow[]> {
    const params: QueryParams = {
      ...query,
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
    };
    return this.api.get('/api/v1/clinic/reports/break-glass-compliance', params);
  }

  exportReport(
    exportType: string,
    from?: string,
    to?: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicAuditExportView> {
    const params: QueryParams = {
      ...query,
      exportType,
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
    };
    return this.api.post('/api/v1/clinic/reports/export', {}, params);
  }

  // ---- Phase 4 --- Lab pipeline ----------------------------------------

  /** `GET /api/v1/clinic/lab-orders` — the prosthetic pipeline, newest first. */
  labOrders(
    query: ClinicPatientQuery & { patientId?: string; status?: string } = {},
  ): Promise<ClinicLabOrder[]> {
    return this.api.get('/api/v1/clinic/lab-orders', query);
  }

  /** `POST /api/v1/clinic/lab-orders` — send a case to the lab. */
  createLabOrder(
    payload: CreateLabOrderPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicLabOrder> {
    return this.api.post('/api/v1/clinic/lab-orders', payload, query);
  }

  /** `PATCH /api/v1/clinic/lab-orders/{orderId}/status` — move the case. */
  updateLabOrderStatus(
    orderId: string,
    status: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicLabOrder> {
    return this.api.patch(
      `/api/v1/clinic/lab-orders/${encodeURIComponent(orderId)}/status`,
      { status },
      query,
    );
  }

  /** `DELETE /api/v1/clinic/lab-orders/{orderId}` — ADMIN only on the server. */
  deleteLabOrder(orderId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(
      `/api/v1/clinic/lab-orders/${encodeURIComponent(orderId)}`,
      query,
    );
  }

  // ---- Phase 4 --- Clear Aligner Lab ------------------------------------

  /** `GET /api/v1/clinic/aligner-cases`. */
  alignerCases(
    query: ClinicPatientQuery & { status?: string } = {},
  ): Promise<ClinicAlignerCase[]> {
    return this.api.get('/api/v1/clinic/aligner-cases', query);
  }

  /** `POST /api/v1/clinic/aligner-cases`. */
  createAlignerCase(
    payload: CreateAlignerCasePayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicAlignerCase> {
    return this.api.post('/api/v1/clinic/aligner-cases', payload, query);
  }

  /** `PATCH /api/v1/clinic/aligner-cases/{caseId}/status`. */
  updateAlignerCaseStatus(
    caseId: string,
    status: string,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicAlignerCase> {
    return this.api.patch(
      `/api/v1/clinic/aligner-cases/${encodeURIComponent(caseId)}/status`,
      { status },
      query,
    );
  }

  /** `DELETE /api/v1/clinic/aligner-cases/{caseId}` — ADMIN only on the server. */
  deleteAlignerCase(caseId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(
      `/api/v1/clinic/aligner-cases/${encodeURIComponent(caseId)}`,
      query,
    );
  }

  // ---- Phase 4 --- Patient Feedback -------------------------------------

  /** `GET /api/v1/clinic/feedback`, filtered by `q`, `doctorId` and `minRating`. */
  feedbackList(
    query: ClinicPatientQuery & { doctorId?: string; minRating?: number } = {},
  ): Promise<ClinicFeedback[]> {
    return this.api.get('/api/v1/clinic/feedback', query);
  }

  /** `POST /api/v1/clinic/feedback`. */
  createFeedback(
    payload: CreateFeedbackPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicFeedback> {
    return this.api.post('/api/v1/clinic/feedback', payload, query);
  }

  /** `DELETE /api/v1/clinic/feedback/{feedbackId}` — ADMIN only on the server. */
  deleteFeedback(feedbackId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(
      `/api/v1/clinic/feedback/${encodeURIComponent(feedbackId)}`,
      query,
    );
  }

  // ---- Phase 4 --- Doctor commission and payouts -------------------------

  /** `GET /api/v1/clinic/doctor-commission` — ADMIN-only earnings window. */
  doctorCommission(
    from?: string,
    to?: string,
    query: ClinicPatientQuery = {},
  ): Promise<DoctorCommission[]> {
    const params: QueryParams = {
      ...query,
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
    };
    return this.api.get('/api/v1/clinic/doctor-commission', params);
  }

  /** `GET /api/v1/clinic/doctor-payouts`. */
  doctorPayouts(
    query: ClinicPatientQuery & { doctorId?: string; status?: string } = {},
  ): Promise<DoctorPayout[]> {
    return this.api.get('/api/v1/clinic/doctor-payouts', query);
  }

  /** `POST /api/v1/clinic/doctor-payouts` — server computes the amount. */
  createDoctorPayout(
    payload: CreateDoctorPayoutPayload,
    query: ClinicPatientQuery = {},
  ): Promise<DoctorPayout> {
    return this.api.post('/api/v1/clinic/doctor-payouts', payload, query);
  }

  /** `PATCH /api/v1/clinic/doctor-payouts/{payoutId}/status`. */
  updateDoctorPayoutStatus(
    payoutId: string,
    status: string,
    query: ClinicPatientQuery = {},
  ): Promise<DoctorPayout> {
    return this.api.patch(
      `/api/v1/clinic/doctor-payouts/${encodeURIComponent(payoutId)}/status`,
      { status },
      query,
    );
  }

  // ---- Requirement 1: Inventory & Service-to-Material Mapping --------------

  inventoryItems(
    query: ClinicPatientQuery & { clinicId?: string; q?: string; lowStockOnly?: boolean } = {},
  ): Promise<InventoryItemRow[]> {
    return this.api.get('/api/v1/clinic/inventory/items', query);
  }

  consolidatedInventory(
    query: ClinicPatientQuery & { q?: string } = {},
  ): Promise<ConsolidatedInventoryRow[]> {
    return this.api.get('/api/v1/clinic/inventory/consolidated', query);
  }

  createInventoryItem(
    payload: CreateInventoryItemPayload,
    query: ClinicPatientQuery = {},
  ): Promise<InventoryItemRow> {
    return this.api.post('/api/v1/clinic/inventory/items', payload, query);
  }

  updateInventoryItem(
    itemId: string,
    payload: UpdateInventoryItemPayload,
    query: ClinicPatientQuery = {},
  ): Promise<InventoryItemRow> {
    return this.api.patch(`/api/v1/clinic/inventory/items/${encodeURIComponent(itemId)}`, payload, query);
  }

  adjustInventoryStock(
    itemId: string,
    payload: AdjustInventoryStockPayload,
    query: ClinicPatientQuery = {},
  ): Promise<InventoryItemRow> {
    return this.api.post(`/api/v1/clinic/inventory/items/${encodeURIComponent(itemId)}/adjust`, payload, query);
  }

  deleteInventoryItem(itemId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(`/api/v1/clinic/inventory/items/${encodeURIComponent(itemId)}`, query);
  }

  inventoryTransactions(
    query: ClinicPatientQuery & { itemId?: string; transactionType?: string; limit?: number } = {},
  ): Promise<InventoryTransactionRow[]> {
    return this.api.get('/api/v1/clinic/inventory/transactions', query);
  }

  checkInventoryAvailability(
    procedureId: string,
    quantity: number = 1,
    query: ClinicPatientQuery = {},
  ): Promise<InventoryStockWarning[]> {
    return this.api.get('/api/v1/clinic/inventory/check-availability', {
      ...query,
      procedureId,
      quantity,
    });
  }

  getProcedureMaterials(
    procedureId: string,
    query: ClinicPatientQuery = {},
  ): Promise<ServiceMaterialRow[]> {
    return this.api.get(`/api/v1/clinic/procedures/${encodeURIComponent(procedureId)}/materials`, query);
  }

  saveProcedureMaterials(
    procedureId: string,
    materials: readonly ServiceMaterialInput[],
    query: ClinicPatientQuery = {},
  ): Promise<ServiceMaterialRow[]> {
    return this.api.put(
      `/api/v1/clinic/procedures/${encodeURIComponent(procedureId)}/materials`,
      materials,
      query,
    );
  }

  // ---- Requirement 2: HR Lite — Staff Schedules & Leave Requests -----------

  hrSchedules(
    query: ClinicPatientQuery & {
      userId?: string;
      doctorId?: string;
      mineOnly?: boolean;
      scheduleType?: string;
    } = {},
  ): Promise<StaffScheduleRow[]> {
    return this.api.get('/api/v1/clinic/hr/schedules', query);
  }

  createHrSchedule(
    payload: CreateStaffSchedulePayload,
    query: ClinicPatientQuery = {},
  ): Promise<StaffScheduleRow> {
    return this.api.post('/api/v1/clinic/hr/schedules', payload, query);
  }

  updateHrSchedule(
    scheduleId: string,
    payload: Partial<CreateStaffSchedulePayload>,
    query: ClinicPatientQuery = {},
  ): Promise<StaffScheduleRow> {
    return this.api.patch(`/api/v1/clinic/hr/schedules/${encodeURIComponent(scheduleId)}`, payload, query);
  }

  deleteHrSchedule(scheduleId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(`/api/v1/clinic/hr/schedules/${encodeURIComponent(scheduleId)}`, query);
  }

  hrLeaveRequests(
    query: ClinicPatientQuery & { status?: string; mineOnly?: boolean } = {},
  ): Promise<LeaveRequestRow[]> {
    return this.api.get('/api/v1/clinic/hr/leave-requests', query);
  }

  createHrLeaveRequest(
    payload: CreateLeaveRequestPayload,
    query: ClinicPatientQuery = {},
  ): Promise<LeaveRequestRow> {
    return this.api.post('/api/v1/clinic/hr/leave-requests', payload, query);
  }

  hrLeaveConflicts(requestId: string, query: ClinicPatientQuery = {}): Promise<LeaveConflictReport> {
    return this.api.get(`/api/v1/clinic/hr/leave-requests/${encodeURIComponent(requestId)}/conflicts`, query);
  }

  reviewHrLeaveRequest(
    requestId: string,
    payload: ReviewLeaveRequestPayload,
    query: ClinicPatientQuery = {},
  ): Promise<LeaveRequestRow> {
    return this.api.patch(
      `/api/v1/clinic/hr/leave-requests/${encodeURIComponent(requestId)}/decision`,
      payload,
      query,
    );
  }

  // ---- Requirement 3: Financial Management & P&L Analytics -----------------

  financeExpenses(
    query: ClinicPatientQuery & { clinicId?: string; category?: string; from?: string; to?: string } = {},
  ): Promise<ClinicExpenseRow[]> {
    return this.api.get('/api/v1/clinic/finance/expenses', query);
  }

  createFinanceExpense(
    payload: CreateClinicExpensePayload,
    query: ClinicPatientQuery = {},
  ): Promise<ClinicExpenseRow> {
    return this.api.post('/api/v1/clinic/finance/expenses', payload, query);
  }

  deleteFinanceExpense(expenseId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(`/api/v1/clinic/finance/expenses/${encodeURIComponent(expenseId)}`, query);
  }

  recurringExpenseTemplates(
    query: ClinicPatientQuery & { clinicId?: string } = {},
  ): Promise<RecurringExpenseTemplateRow[]> {
    return this.api.get('/api/v1/clinic/finance/recurring-templates', query);
  }

  createRecurringExpenseTemplate(
    payload: CreateRecurringTemplatePayload,
    query: ClinicPatientQuery = {},
  ): Promise<RecurringExpenseTemplateRow> {
    return this.api.post('/api/v1/clinic/finance/recurring-templates', payload, query);
  }

  confirmRecurringExpenseTemplate(
    templateId: string,
    payload: ConfirmRecurringTemplatePayload = {},
    query: ClinicPatientQuery = {},
  ): Promise<ClinicExpenseRow> {
    return this.api.post(
      `/api/v1/clinic/finance/recurring-templates/${encodeURIComponent(templateId)}/confirm`,
      payload,
      query,
    );
  }

  deleteRecurringExpenseTemplate(templateId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(
      `/api/v1/clinic/finance/recurring-templates/${encodeURIComponent(templateId)}`,
      query,
    );
  }

  financialAnalytics(
    query: ClinicPatientQuery & { year?: number; periodType?: string; clinicId?: string } = {},
  ): Promise<FinancialAnalyticsReport> {
    return this.api.get('/api/v1/clinic/finance/analytics', query);
  }

  // ---- Requirement 4: Treatment Rooms --------------------------------------

  treatmentRooms(
    query: ClinicPatientQuery & { clinicId?: string } = {},
  ): Promise<TreatmentRoomRow[]> {
    return this.api.get('/api/v1/clinic/treatment-rooms', query);
  }

  createTreatmentRoom(
    payload: CreateTreatmentRoomPayload,
    query: ClinicPatientQuery = {},
  ): Promise<TreatmentRoomRow> {
    return this.api.post('/api/v1/clinic/treatment-rooms', payload, query);
  }

  updateTreatmentRoom(
    roomId: string,
    payload: Partial<CreateTreatmentRoomPayload>,
    query: ClinicPatientQuery = {},
  ): Promise<TreatmentRoomRow> {
    return this.api.patch(`/api/v1/clinic/treatment-rooms/${encodeURIComponent(roomId)}`, payload, query);
  }

  deleteTreatmentRoom(roomId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(`/api/v1/clinic/treatment-rooms/${encodeURIComponent(roomId)}`, query);
  }

  // ---- Requirement 5: Dental Imaging & Preferred External Labs -------------

  imagingLabs(query: ClinicPatientQuery & { clinicId?: string } = {}): Promise<ImagingLabRow[]> {
    return this.api.get('/api/v1/clinic/imaging/labs', query);
  }

  createImagingLab(
    payload: CreateImagingLabPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ImagingLabRow> {
    return this.api.post('/api/v1/clinic/imaging/labs', payload, query);
  }

  updateImagingLab(
    labId: string,
    payload: Partial<CreateImagingLabPayload>,
    query: ClinicPatientQuery = {},
  ): Promise<ImagingLabRow> {
    return this.api.patch(`/api/v1/clinic/imaging/labs/${encodeURIComponent(labId)}`, payload, query);
  }

  deleteImagingLab(labId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(`/api/v1/clinic/imaging/labs/${encodeURIComponent(labId)}`, query);
  }

  imagingRequests(
    query: ClinicPatientQuery & { patientId?: string; status?: string; imagingType?: string } = {},
  ): Promise<ImagingRequestRow[]> {
    return this.api.get('/api/v1/clinic/imaging/requests', query);
  }

  imagingAnalytics(query: ClinicPatientQuery = {}): Promise<ImagingSummaryAnalytics> {
    return this.api.get('/api/v1/clinic/imaging/analytics', query);
  }

  createImagingRequest(
    payload: CreateImagingRequestPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ImagingRequestRow> {
    return this.api.post('/api/v1/clinic/imaging/requests', payload, query);
  }

  updateImagingRequest(
    requestId: string,
    payload: UpdateImagingRequestPayload,
    query: ClinicPatientQuery = {},
  ): Promise<ImagingRequestRow> {
    return this.api.patch(`/api/v1/clinic/imaging/requests/${encodeURIComponent(requestId)}`, payload, query);
  }

  deleteImagingRequest(requestId: string, query: ClinicPatientQuery = {}): Promise<boolean> {
    return this.api.delete(`/api/v1/clinic/imaging/requests/${encodeURIComponent(requestId)}`, query);
  }
}

export interface ClinicRelationship {
  readonly id: string;
  readonly patientId: string;
  readonly relatedPatientId?: string | null;
  readonly relatedPatientName?: string | null;
  readonly relationshipType: string;
  readonly customRelationship?: string | null;
  readonly relatedPersonName?: string | null;
  readonly relatedPersonPhone?: string | null;
  readonly guarantor: boolean;
  readonly emergencyContact: boolean;
  readonly notes?: string | null;
  readonly createdAt: string;
}

export interface CreateRelationshipPayload {
  readonly relatedPatientId?: string;
  readonly relationshipType: string;
  readonly customRelationship?: string;
  readonly relatedPersonName?: string;
  readonly relatedPersonPhone?: string;
  readonly isGuarantor?: boolean;
  readonly isEmergencyContact?: boolean;
  readonly notes?: string;
}

export interface ClinicConsent {
  readonly id: string;
  readonly patientId: string;
  readonly consentType: string;
  readonly status: 'GRANTED' | 'REVOKED' | 'EXPIRED' | string;
  readonly grantedAt: string;
  readonly revokedAt?: string | null;
  readonly revocationReason?: string | null;
  readonly expiresAt?: string | null;
  readonly signedBy: string;
  readonly signerRelationship?: string | null;
  readonly witnessName?: string | null;
  readonly documentReference?: string | null;
  readonly notes?: string | null;
  readonly createdAt: string;
}

export interface CreateConsentPayload {
  readonly consentType: string;
  readonly expiresAt?: string;
  readonly signedBy: string;
  readonly signerRelationship?: string;
  readonly witnessName?: string;
  readonly documentReference?: string;
  readonly notes?: string;
}

export interface ClinicDisclosure {
  readonly id: string;
  readonly patientId: string;
  readonly disclosedTo: string;
  readonly purpose: string;
  readonly recordsDisclosed: string;
  readonly disclosedAt: string;
  readonly disclosedBy?: string | null;
  readonly disclosedByName?: string | null;
  readonly createdAt: string;
}

export interface LogDisclosurePayload {
  readonly disclosedTo: string;
  readonly purpose: string;
  readonly recordsDisclosed: string;
}

export interface MergePatientsPayload {
  readonly sourcePatientId: string;
  readonly targetPatientId: string;
  readonly reason: string;
}

export interface MergePatientsResult {
  readonly mergeId: string;
  readonly sourcePatientId: string;
  readonly targetPatientId: string;
  readonly status: string;
  readonly appointmentsMoved: number;
  readonly encountersMoved: number;
  readonly plansMoved: number;
  readonly invoicesMoved: number;
}

/** Treatment plan status (TRT-05, Slice S5) */
export type TreatmentPlanStatus = 'DRAFT' | 'PRESENTED' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED';

/** Treatment plan procedure item status (TRT-05) */
export type TreatmentPlanItemStatus = 'PROPOSED' | 'APPROVED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

/** Header of a treatment plan with aggregated financials, versioning, and consent info */
export interface ClinicTreatmentPlanHeader {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId: string;
  readonly title: string;
  readonly status: TreatmentPlanStatus;
  readonly version: number;
  readonly parentPlanId?: string;
  readonly planType?: string;
  readonly totalDiscount?: number;
  readonly insuranceEstimatedCoverage?: number;
  readonly patientEstimatedPortion?: number;
  readonly patientConsent: boolean;
  readonly consentAt?: string;
  readonly consentByUserId?: string;
  readonly consentRegistrarName?: string;
  readonly consentNotes?: string;
  readonly approvedAt?: string;
  readonly approvedByUserId?: string;
  readonly approvedByUserName?: string;
  readonly cancellationReason?: string;
  readonly cancelledAt?: string;
  readonly notes?: string;
  readonly totalEstimatedCost: number;
  readonly totalInvoicedAmount: number;
  readonly totalCollectedAmount: number;
  readonly itemCount: number;
  readonly completedItemCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** One procedure item in a treatment plan (TRT-01, TRT-02, TRT-03, Slice S5 stages & multi-visit) */
export interface ClinicTreatmentPlanItem {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly planId: string;
  readonly sequenceOrder: number;
  readonly stage?: string;
  readonly plannedVisits?: number;
  readonly completedVisits?: number;
  readonly procedureId?: string;
  readonly procedureName: string;
  readonly toothFdi?: number;
  readonly doctorId?: string;
  readonly doctorName?: string;
  readonly estimatedCost: number;
  readonly discountAmount?: number;
  readonly insuranceCoverage?: number;
  readonly patientPortion?: number;
  readonly invoicedAmount: number;
  readonly collectedAmount: number;
  readonly status: TreatmentPlanItemStatus;
  readonly notes?: string;
  readonly outcomeNotes?: string;
  readonly completedAt?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** Treatment plan detail with all items */
export interface ClinicTreatmentPlanDetail {
  readonly plan: ClinicTreatmentPlanHeader;
  readonly items: readonly ClinicTreatmentPlanItem[];
}

export interface CreateTreatmentPlanPayload {
  readonly title: string;
  readonly notes?: string;
  readonly projectId?: string;
  readonly planType?: string;
  readonly parentPlanId?: string;
  readonly totalDiscount?: number;
  readonly insuranceEstimatedCoverage?: number;
  readonly patientEstimatedPortion?: number;
}

export interface ForkTreatmentPlanPayload {
  readonly title?: string;
  readonly changeReason?: string;
}

export interface ApproveTreatmentPlanPayload {
  readonly consentNotes?: string;
}

export interface CancelTreatmentPlanPayload {
  readonly cancellationReason?: string;
}

export interface UpdateTreatmentPlanStatusPayload {
  readonly status: TreatmentPlanStatus;
  readonly notes?: string;
}

export interface RecordTreatmentPlanConsentPayload {
  readonly patientConsent: boolean;
  readonly consentNotes?: string;
}

export interface CreateTreatmentPlanItemPayload {
  readonly sequenceOrder?: number;
  readonly stage?: string;
  readonly plannedVisits?: number;
  readonly procedureId?: string;
  readonly procedureName: string;
  readonly toothFdi?: number;
  readonly doctorId?: string;
  readonly estimatedCost?: number;
  readonly discountAmount?: number;
  readonly insuranceCoverage?: number;
  readonly patientPortion?: number;
  readonly notes?: string;
}

export interface UpdateTreatmentPlanItemPayload {
  readonly sequenceOrder?: number;
  readonly stage?: string;
  readonly plannedVisits?: number;
  readonly completedVisits?: number;
  readonly procedureId?: string;
  readonly procedureName?: string;
  readonly toothFdi?: number;
  readonly doctorId?: string;
  readonly estimatedCost?: number;
  readonly discountAmount?: number;
  readonly insuranceCoverage?: number;
  readonly patientPortion?: number;
  readonly invoicedAmount?: number;
  readonly collectedAmount?: number;
  readonly status?: TreatmentPlanItemStatus;
  readonly notes?: string;
  readonly outcomeNotes?: string;
}

/** Clinical encounter status */
export type EncounterStatus = 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

/** Clinical encounter header row (CLN-01 to CLN-05, Slice S3) */
export interface ClinicEncounterHeader {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId: string;
  readonly patientName?: string;
  readonly patientNo?: string;
  readonly appointmentId?: string;
  readonly doctorId: string;
  readonly doctorName?: string;
  readonly treatmentPlanId?: string;
  readonly treatmentPlanTitle?: string;
  readonly treatmentPlanItemId?: string;
  readonly treatmentPlanProcedureName?: string;
  readonly chiefComplaint: string;
  readonly examinationNotes?: string;
  readonly diagnosis: string;
  readonly diagnosisUpdatedAt?: string;
  readonly diagnosisUpdatedBy?: string;
  readonly diagnosisUpdatedByName?: string;
  readonly clinicalNotes?: string;
  readonly performedProcedures?: string;
  readonly toothFdi?: number;
  readonly toothLocation?: string;
  readonly status: EncounterStatus;
  readonly visitDate: string;
  readonly durationMinutes: number;
  readonly prescriptionCount: number;
  readonly isMedicalDataMasked: boolean;
  readonly bloodPressureSystolic?: number;
  readonly bloodPressureDiastolic?: number;
  readonly pulseRate?: number;
  readonly temperatureC?: number;
  readonly signedAt?: string;
  readonly signedByUserId?: string;
  readonly signedByUserName?: string;
  readonly signatureHash?: string;
  readonly isSigned?: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** Prescription row attached to an encounter (CLN-06) */
export interface ClinicPrescriptionRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly encounterId: string;
  readonly patientId: string;
  readonly doctorId: string;
  readonly doctorName?: string;
  readonly medicationName: string;
  readonly dosage: string;
  readonly frequency: string;
  readonly duration: string;
  readonly instructions?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** Immutable signed note amendment audit row (Slice S3) */
export interface ClinicNoteAmendment {
  readonly id: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly encounterId: string;
  readonly amendedByUserId: string;
  readonly amendedByUserName?: string;
  readonly amendedAt: string;
  readonly reason: string;
  readonly amendmentContent: string;
  readonly signatureHash: string;
  readonly createdAt: string;
}

/** Full encounter detail with issued prescriptions and amendments */
export interface ClinicEncounterDetail {
  readonly encounter: ClinicEncounterHeader;
  readonly prescriptions: readonly ClinicPrescriptionRow[];
  readonly amendments?: readonly ClinicNoteAmendment[];
}

export interface CreateEncounterPayload {
  readonly doctorId: string;
  readonly appointmentId?: string;
  readonly treatmentPlanId?: string;
  readonly treatmentPlanItemId?: string;
  readonly chiefComplaint: string;
  readonly examinationNotes?: string;
  readonly diagnosis: string;
  readonly clinicalNotes?: string;
  readonly performedProcedures?: string;
  readonly toothFdi?: number;
  readonly toothLocation?: string;
  readonly status?: EncounterStatus;
  readonly visitDate?: string;
  readonly durationMinutes?: number;
  readonly projectId?: string;
  readonly completeLinkedPlanItem?: boolean;
  readonly bloodPressureSystolic?: number;
  readonly bloodPressureDiastolic?: number;
  readonly pulseRate?: number;
  readonly temperatureC?: number;
  readonly prescriptions?: readonly CreatePrescriptionPayload[];
}

export interface UpdateDiagnosisPayload {
  readonly diagnosis: string;
  readonly clinicalNotes?: string;
  readonly examinationNotes?: string;
  readonly performedProcedures?: string;
  readonly toothFdi?: number;
  readonly toothLocation?: string;
  readonly bloodPressureSystolic?: number;
  readonly bloodPressureDiastolic?: number;
  readonly pulseRate?: number;
  readonly temperatureC?: number;
}

export interface CreateAmendmentPayload {
  readonly reason: string;
  readonly amendmentContent: string;
}

/** Comprehensive patient medical history record (Slice S3) */
export interface ClinicMedicalHistory {
  readonly id: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly patientId: string;
  readonly allergies?: string;
  readonly conditions?: string;
  readonly currentMedications?: string;
  readonly bleedingDisorder: boolean;
  readonly cardiacCondition: boolean;
  readonly diabetes: boolean;
  readonly notes?: string;
  readonly recordedByUserId?: string;
  readonly recordedByUserName?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** Request payload to save/update medical history (Slice S3) */
export interface SaveMedicalHistoryPayload {
  readonly allergies?: string;
  readonly conditions?: string;
  readonly currentMedications?: string;
  readonly bleedingDisorder?: boolean;
  readonly cardiacCondition?: boolean;
  readonly diabetes?: boolean;
  readonly notes?: string;
}

export interface CreatePrescriptionPayload {
  readonly medicationName: string;
  readonly dosage: string;
  readonly frequency: string;
  readonly duration: string;
  readonly instructions?: string;
  readonly doctorId?: string;
}

export interface ClinicPrescriptionSheet {
  readonly id: string;
  readonly rxNumber: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId: string;
  readonly patientName: string;
  readonly patientNo?: string;
  readonly patientPhone?: string;
  readonly doctorId: string;
  readonly doctorName: string;
  readonly doctorSpecialty?: string;
  readonly diagnosis: string;
  readonly notes?: string;
  readonly visitDate: string;
  readonly createdAt: string;
  readonly medicationCount: number;
  readonly medications: readonly ClinicPrescriptionRow[];
}

export interface CreateStandalonePrescriptionPayload {
  readonly patientId: string;
  readonly doctorId?: string;
  readonly diagnosis: string;
  readonly notes?: string;
  readonly visitDate?: string;
  readonly projectId?: string;
  readonly medications: readonly CreatePrescriptionPayload[];
}

export interface ClinicLetterheadSettings {
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly clinicName: string;
  readonly clinicSubtitle: string;
  readonly logoUrl?: string | null;
  readonly address: string;
  readonly phone: string;
  readonly whatsapp?: string | null;
  readonly email?: string | null;
  readonly licenseNumber?: string | null;
  readonly defaultInstructions?: string | null;
  readonly footerText?: string | null;
  readonly signatureLabel: string;
  readonly paperSize: string;
  readonly prePrintedPaper: boolean;
  readonly updatedAt?: string;
}

export interface UpdateLetterheadPayload {
  readonly clinicName?: string;
  readonly clinicSubtitle?: string;
  readonly logoUrl?: string | null;
  readonly address?: string;
  readonly phone?: string;
  readonly whatsapp?: string | null;
  readonly email?: string | null;
  readonly licenseNumber?: string | null;
  readonly defaultInstructions?: string | null;
  readonly footerText?: string | null;
  readonly signatureLabel?: string;
  readonly paperSize?: string;
  readonly prePrintedPaper?: boolean;
  readonly projectId?: string;
}

// ---- Invoices & Payments Models (BIL-01 to BIL-09) ------------------------

/** Invoice status (BIL-01 to BIL-05) */
export type ClinicInvoiceStatus = 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'CANCELLED';

/** Payment method (BIL-02) */
export type ClinicPaymentMethod = 'CASH' | 'CARD' | 'BANK_TRANSFER';

export interface ClinicInvoiceHeader {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId: string;
  readonly patientName?: string;
  readonly patientNo?: string;
  readonly encounterId?: string;
  readonly treatmentPlanId?: string;
  readonly treatmentPlanTitle?: string;
  readonly invoiceNumber: string;
  readonly currency: 'JOD' | 'USD';
  readonly subtotal: number;
  readonly discountAmount: number;
  readonly discountReason?: string;
  readonly discountAppliedBy?: string;
  readonly discountAppliedByName?: string;
  readonly totalAmount: number;
  readonly paidAmount: number;
  readonly balanceDue: number;
  readonly status: ClinicInvoiceStatus;
  readonly notes?: string;
  readonly issuedAt: string;
  readonly cancelledAt?: string;
  readonly cancelledBy?: string;
  readonly cancelledByName?: string;
  readonly cancellationReason?: string;
  readonly itemCount: number;
  readonly paymentCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ClinicInvoiceItemRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly invoiceId: string;
  readonly treatmentPlanItemId?: string;
  readonly procedureId?: string;
  readonly procedureName?: string;
  readonly itemType: 'PROCEDURE' | 'CONSULTATION' | 'MANUAL';
  readonly description: string;
  readonly toothFdi?: number;
  readonly unitPrice: number;
  readonly quantity: number;
  readonly totalPrice: number;
  readonly createdAt: string;
}

export interface ClinicPaymentRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly invoiceId: string;
  readonly invoiceNumber?: string;
  readonly patientId: string;
  readonly patientName?: string;
  readonly paymentNumber: string;
  readonly amount: number;
  readonly currency: string;
  readonly paymentMethod: ClinicPaymentMethod;
  readonly referenceNumber?: string;
  readonly notes?: string;
  readonly recordedByUserId: string;
  readonly recordedByUserName?: string;
  readonly paymentDate: string;
  readonly createdAt: string;
}

export interface ClinicInvoiceDetail {
  readonly invoice: ClinicInvoiceHeader;
  readonly items: readonly ClinicInvoiceItemRow[];
  readonly payments: readonly ClinicPaymentRow[];
}

export interface CreateInvoiceItemPayload {
  readonly treatmentPlanItemId?: string;
  readonly procedureId?: string;
  readonly itemType?: 'PROCEDURE' | 'CONSULTATION' | 'MANUAL';
  readonly description: string;
  readonly toothFdi?: number;
  readonly unitPrice: number;
  readonly quantity?: number;
}

export interface CreateInvoicePayload {
  readonly patientId: string;
  readonly encounterId?: string;
  readonly treatmentPlanId?: string;
  readonly currency?: 'JOD' | 'USD';
  readonly notes?: string;
  readonly items: readonly CreateInvoiceItemPayload[];
}

export interface ApplyDiscountPayload {
  readonly discountAmount: number;
  readonly discountReason: string;
}

export interface CancelInvoicePayload {
  readonly cancellationReason: string;
}

export interface RecordPaymentPayload {
  readonly amount: number;
  readonly currency?: string;
  readonly paymentMethod: ClinicPaymentMethod;
  readonly referenceNumber?: string;
  readonly notes?: string;
  readonly idempotencyKey?: string;
}

export interface ClinicPaymentReceipt {
  readonly paymentId: string;
  readonly paymentNumber: string;
  readonly invoiceId: string;
  readonly invoiceNumber: string;
  readonly clinicName: string;
  readonly patientId: string;
  readonly patientName: string;
  readonly patientNo?: string;
  readonly amount: number;
  readonly currency: string;
  readonly paymentMethod: string;
  readonly referenceNumber?: string;
  readonly notes?: string;
  readonly paymentDate: string;
  readonly recordedByUserName: string;
  readonly remainingInvoiceBalance: number;
}

export interface RecordRefundPayload {
  readonly amount: number;
  readonly currency?: string;
  readonly refundMethod: 'CASH' | 'CARD' | 'BANK_TRANSFER' | 'STORE_CREDIT';
  readonly reason: string;
  readonly paymentId?: string;
}

export interface ClinicRefundRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly invoiceId: string;
  readonly invoiceNumber: string;
  readonly paymentId?: string;
  readonly paymentNumber?: string;
  readonly patientId: string;
  readonly patientName: string;
  readonly refundNumber: string;
  readonly amount: number;
  readonly currency: string;
  readonly refundMethod: string;
  readonly reason: string;
  readonly approvedByUserId: string;
  readonly approvedByUserName?: string;
  readonly refundDate: string;
  readonly createdAt: string;
}

export interface PatientLedgerEntryRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId: string;
  readonly entryType: 'INVOICE_CHARGE' | 'DISCOUNT_CREDIT' | 'PAYMENT_CREDIT' | 'REFUND_DEBIT' | 'CANCELLATION_CREDIT' | 'ADJUSTMENT' | string;
  readonly direction: 'DEBIT' | 'CREDIT';
  readonly amount: number;
  readonly currency: string;
  readonly invoiceId?: string;
  readonly invoiceNumber?: string;
  readonly paymentId?: string;
  readonly paymentNumber?: string;
  readonly refundId?: string;
  readonly refundNumber?: string;
  readonly referenceNumber: string;
  readonly description: string;
  readonly recordedByUserId: string;
  readonly recordedByUserName?: string;
  readonly entryDate: string;
  readonly createdAt: string;
}

export interface GenerateInvoiceFromPlanPayload {
  readonly patientId: string;
  readonly treatmentPlanId: string;
  readonly treatmentPlanItemIds?: readonly string[];
  readonly currency?: 'JOD' | 'USD';
  readonly notes?: string;
}

export interface PatientStatement {
  readonly patientId: string;
  readonly patientName: string;
  readonly patientNo: string;
  readonly currency: string;
  readonly fromDate?: string;
  readonly toDate?: string;
  readonly totalDebits: number;
  readonly totalCredits: number;
  readonly netBalanceDue: number;
  readonly entries: readonly PatientLedgerEntryRow[];
}

// ---- Policies Models (USR-09, BIL-07) -------------------------------------

export interface ClinicPolicyRow {
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly doctorSeesAllPatients: boolean;
  readonly defaultSlotDurationMinutes: number;
  readonly allowDoubleBooking: boolean;
  readonly primaryCurrency: 'JOD' | 'USD';
  readonly toothNotationSystem: 'FDI' | 'UNIVERSAL';
  readonly updatedAt?: string;
  readonly updatedByName?: string;
}

export interface UpdatePolicyPayload {
  readonly doctorSeesAllPatients?: boolean;
  readonly defaultSlotDurationMinutes?: number;
  readonly allowDoubleBooking?: boolean;
  readonly primaryCurrency?: 'JOD' | 'USD';
  readonly toothNotationSystem?: 'FDI' | 'UNIVERSAL';
}

// ---- Reports Models (RPT-01 to RPT-05, BIL-08) ----------------------------

export interface FinancialSummaryRow {
  readonly currency: string;
  readonly totalInvoiced: number;
  readonly totalCollected: number;
  readonly totalDiscount: number;
  readonly totalBalanceDue: number;
  readonly invoiceCount: number;
  readonly paymentCount: number;
}

export interface DoctorProductivityRow {
  readonly doctorId: string;
  readonly doctorName: string;
  readonly specialty?: string;
  readonly appointmentCount: number;
  readonly encounterCount: number;
  readonly totalInvoicedAmount: number;
  readonly totalCollectedAmount: number;
}

export interface AppointmentMetrics {
  readonly totalAppointments: number;
  readonly completedCount: number;
  readonly confirmedCount: number;
  readonly cancelledCount: number;
  readonly noShowCount: number;
  readonly cancellationRatePercent: number;
}

export interface PatientMetrics {
  readonly totalPatients: number;
  readonly newPatientsInWindow: number;
}

export interface ClinicReportView {
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly windowLabel: string;
  readonly financials: readonly FinancialSummaryRow[];
  readonly doctorProductivity: readonly DoctorProductivityRow[];
  readonly appointments: AppointmentMetrics;
  readonly patients: PatientMetrics;
  readonly patientAgeDistribution?: PatientAgeDistribution | null;
}

/**
 * The patients who visited inside the report window, banded by age.
 *
 * Server-computed on purpose. The client used to band the patient list it had
 * been handed, which was the first page of a paged endpoint: the three bands
 * then summed to fewer patients than the clinic actually had, and the panel
 * contradicted the Total Number of Patients card beside it.
 */
export interface PatientAgeDistribution {
  readonly age0to25: number;
  readonly age26to50: number;
  readonly age50Plus: number;
}

export interface ClinicAiRun {
  readonly id: string;
  readonly projectId: string;
  readonly conversationId: string;
  readonly agentId: string;
  readonly status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED' | string;
  readonly decision?: string | null;
  readonly confidence?: number | null;
  readonly decisionReason?: string | null;
  readonly provider: string;
  readonly model?: string | null;
  readonly errorMessage?: string | null;
  readonly latencyMs?: number | null;
  readonly startedAt: string;
  readonly finishedAt?: string | null;
}

export interface ClinicAiOverview {
  readonly totalRuns: number;
  readonly successfulRuns: number;
  readonly failedRuns: number;
  readonly avgLatencyMs: number;
  readonly clinicalSuggestionsDisabled: boolean;
  readonly recentRuns: readonly ClinicAiRun[];
}

export interface ClinicAgentChannel {
  readonly channelKey: string;
  readonly label: string;
  readonly enabled: boolean;
  readonly status: 'CONNECTED' | 'DISCONNECTED';
  readonly identifier: string;
}

export interface ClinicAgentConfig {
  agentName: string;
  personaTone: 'friendly' | 'professional' | 'concise';
  welcomeMessage: string;
  customInstructions: string;
  medicalGuardrailEnabled: boolean;
  selectedModel: 'gemini-2.0-flash' | 'gemini-1.5-pro' | 'gpt-4o-mini';
  temperature: number;
  channels: Record<string, ClinicAgentChannel>;
}

export interface ClinicAgentMetrics {
  readonly customersAnswered: number;
  readonly bookingsCreated: number;
  readonly appointmentsRescheduled: number;
  readonly escalatedToHuman: number;
}

export interface ClinicTestChatResponse {
  readonly reply: string;
  readonly toolUsed: string;
  readonly medicalGuardrailTriggered: boolean;
}

export interface ClinicAiProposalRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId?: string | null;
  readonly patientId?: string | null;
  readonly patientName?: string | null;
  readonly proposalType: 'VISIT_SUMMARY' | 'RECALL_PRIORITIZATION' | 'MISSING_FIELDS' | 'PAYMENT_MATCHING' | 'PATIENT_MESSAGE' | string;
  readonly status: 'DRAFT' | 'APPROVED' | 'REJECTED' | string;
  readonly model: string;
  readonly provider: string;
  readonly promptTemplate: string;
  readonly inputPayloadRedacted: string;
  readonly outputContent: string;
  readonly transparentFactors: string;
  readonly confidencePercent?: number | null;
  readonly phiRedacted: boolean;
  readonly approvedByUserId?: string | null;
  readonly approverName?: string | null;
  readonly approvedAt?: string | null;
  readonly rejectionReason?: string | null;
  readonly createdAt: string;
}

export interface ClinicAiGovernanceRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId?: string | null;
  readonly killSwitchActive: boolean;
  readonly killSwitchReason?: string | null;
  readonly killSwitchEngagedByUserId?: string | null;
  readonly killSwitchEngagedByName?: string | null;
  readonly killSwitchEngagedAt?: string | null;
  readonly clinicalSuggestionsDisabled: boolean;
  readonly updatedAt: string;
}

export interface ToggleKillSwitchPayload {
  readonly killSwitchActive: boolean;
  readonly reason: string;
}

export interface ReviewProposalPayload {
  readonly reviewDecision: 'APPROVED' | 'REJECTED';
  readonly rejectionReason?: string | null;
}

export interface VisitSummaryDraftPayload {
  readonly patientId: string;
  readonly encounterId?: string | null;
}

export interface RecallPrioritizationPayload {
  readonly overdueDaysThreshold?: number | null;
  readonly limit?: number | null;
}

export interface MissingFieldsAuditPayload {
  readonly patientId: string;
}

export interface PaymentMatchProposalPayload {
  readonly paymentId: string;
}

export interface PatientMessageDraftPayload {
  readonly patientId: string;
  readonly messageType?: string | null;
  readonly language?: string | null;
}

/**
 * A possible duplicate, with the score that produced it.
 *
 * The wire shape is `PatientModels.DuplicateCandidate`: `patientId`, `patientNo`,
 * `displayName`, `score`, `matchedOn`.
 *
 * The score is a weighted point total, not a percentage â€” a shared phone number is
 * worth 60 points and a shared surname 10 â€” so it is printed as the server computed
 * it and never turned into a probability. `matchedOn` is the list of fields that
 * matched, joined with `+`, and it is a wire value: it is printed, not interpreted.
 */
export interface ClinicDuplicateCandidate {
  readonly patientId: string;
  readonly patientNo: string;
  readonly displayName: string;
  readonly score: number;
  readonly matchedOn: string;
}

export interface CreatePatientPayload {
  readonly patientNo?: string;
  readonly displayName?: string;
  readonly givenName: string;
  readonly familyName?: string;
  readonly gender?: string;
  readonly dateOfBirth?: string;
  readonly phone: string;
  readonly email?: string;
  readonly cnic?: string;
  readonly password?: string;
  readonly confirmPassword?: string;
  readonly profileImageUrl?: string;
  readonly addressLine?: string;
  readonly country?: string;
  readonly state?: string;
  readonly city?: string;
  readonly postalCode?: string;
  readonly isActive?: boolean;
  readonly locale?: string;
}

export interface ClinicResourceDetail {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly name: string;
  readonly active: boolean;
  readonly capabilities: readonly string[];
  readonly createdAt: string;
}

export interface ClinicHoldRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly doctorId: string;
  readonly resourceId?: string | null;
  readonly slotStartsAt: string;
  readonly durationMinutes: number;
  readonly bufferMinutes: number;
  readonly holdExpiresAt: string;
  readonly heldByUserId?: string | null;
  readonly patientIdentifier?: string | null;
  readonly status: 'ACTIVE' | 'CONFIRMED' | 'EXPIRED' | 'RELEASED' | string;
  readonly createdAt: string;
}

export interface CreateHoldPayload {
  readonly doctorId: string;
  readonly resourceId?: string;
  readonly slotStartsAt: string;
  readonly durationMinutes?: number;
  readonly bufferMinutes?: number;
  readonly holdMinutes?: number;
  readonly patientIdentifier?: string;
}

export interface CreateAppointmentPayload {
  readonly doctorId?: string;
  readonly doctorName?: string;
  readonly patientId?: string;
  readonly patientName?: string;
  readonly patientPhone?: string;
  readonly patientEmail?: string;
  readonly clinicId?: string;
  readonly procedureId?: string;
  readonly service?: string;
  readonly appointmentDate: string;
  readonly durationMinutes?: number;
  readonly bufferMinutes?: number;
  readonly quantity?: number;
  readonly price?: number;
  readonly paymentStatus?: string;
  readonly medicalReportName?: string;
  readonly medicalReportContentType?: string;
  readonly medicalReportUrl?: string;
  readonly resourceId?: string | null;
  readonly notes?: string;
  readonly treatmentRoomId?: string | null;
}

export interface UpdateAppointmentStatusPayload {
  readonly status: string;
  readonly cancelledReason?: string;
}

export interface ClinicWaitlistRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId?: string | null;
  readonly patientName: string;
  readonly patientPhone: string;
  readonly preferredDoctorId?: string | null;
  readonly procedureId?: string | null;
  readonly preferredDays?: string | null;
  readonly preferredTimeOfDay?: string | null;
  readonly priority: 'NORMAL' | 'URGENT' | string;
  readonly status: 'WAITING' | 'OFFERED' | 'BOOKED' | 'EXPIRED' | 'CANCELLED' | string;
  readonly offeredSlotStartsAt?: string | null;
  readonly notes?: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface CreateWaitlistPayload {
  readonly patientId?: string;
  readonly patientName: string;
  readonly patientPhone: string;
  readonly preferredDoctorId?: string;
  readonly procedureId?: string;
  readonly preferredDays?: string;
  readonly preferredTimeOfDay?: string;
  readonly priority?: 'NORMAL' | 'URGENT';
  readonly notes?: string;
}

export interface UpdateWaitlistPayload {
  readonly status: 'WAITING' | 'OFFERED' | 'BOOKED' | 'EXPIRED' | 'CANCELLED';
  readonly offeredSlotStartsAt?: string | null;
}

export interface ClinicReminderRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly appointmentId: string;
  readonly patientId?: string | null;
  readonly channel: 'WHATSAPP' | 'SMS' | string;
  readonly recipientPhone: string;
  readonly scheduledFor: string;
  readonly sentAt?: string | null;
  readonly status: 'PENDING' | 'SENT' | 'FAILED' | 'CANCELLED' | string;
  readonly templateCode: string;
  readonly failureReason?: string | null;
  readonly createdAt: string;
}

export interface CreateReminderPayload {
  readonly appointmentId: string;
  readonly patientId?: string;
  readonly channel?: 'WHATSAPP' | 'SMS';
  readonly recipientPhone: string;
  readonly scheduledFor: string;
  readonly templateCode: string;
}

// ---- Clinical Media Types (Slice S7) ---------------------------------------

export type ClinicMediaCategory =
  | 'RADIOGRAPH_INTRAORAL'
  | 'RADIOGRAPH_PANORAMIC'
  | 'RADIOGRAPH_CEPHALOMETRIC'
  | 'PHOTO_INTRAORAL'
  | 'PHOTO_EXTRAORAL'
  | 'DOCUMENT_PDF'
  | 'CONSENT_FORM'
  | 'LAB_PRESCRIPTION'
  | 'OTHER';

export interface ClinicMediaRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId: string;
  readonly fileName: string;
  readonly contentType: string;
  readonly byteSize: number;
  readonly storageKey: string;
  readonly sha256?: string;
  readonly category: ClinicMediaCategory | string;
  readonly toothFdi?: number | null;
  readonly acquisitionDate: string;
  readonly radiographerName?: string | null;
  readonly encrypted: boolean;
  readonly encryptionAlgorithm?: string;
  readonly scanStatus: string;
  readonly retentionClass?: string;
  readonly retentionExpiresAt?: string | null;
  readonly legalHold: boolean;
  readonly notes?: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ClinicSignedDownloadUrl {
  readonly token: string;
  readonly downloadUrl: string;
  readonly expiresAt: string;
  readonly attachmentId: string;
  readonly fileName: string;
  readonly contentType: string;
  readonly byteSize: number;
}

export interface ClinicMediaAccessAuditRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId: string;
  readonly attachmentId: string;
  readonly action: string;
  readonly accessedByUserId: string;
  readonly accessedByUserName: string;
  readonly accessIp?: string | null;
  readonly notes?: string | null;
  readonly accessedAt: string;
}

export interface UploadMediaPayload {
  readonly file: File;
  readonly category: ClinicMediaCategory | string;
  readonly toothFdi?: number;
  readonly acquisitionDate?: string;
  readonly radiographerName?: string;
  readonly notes?: string;
  readonly retentionClass?: string;
}

// =====================================================================
// Slice S8: Break-Glass Emergency Access, Audit Stream, and Curated Reports
// =====================================================================

export interface BreakGlassGrantRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId?: string | null;
  readonly requestedByUserId: string;
  readonly reason: string;
  readonly grantedAt: string;
  readonly expiresAt: string;
  readonly revokedAt?: string | null;
  readonly revokedByUserId?: string | null;
  readonly revocationReason?: string | null;
  readonly reviewStatus: 'PENDING' | 'APPROVED' | 'FLAGGED' | string;
  readonly reviewedByUserId?: string | null;
  readonly reviewedAt?: string | null;
  readonly reviewNotes?: string | null;
}

export interface CreateBreakGlassRequest {
  readonly patientId?: string;
  readonly reason: string;
  readonly durationHours?: number;
}

export interface ReviewBreakGlassRequest {
  readonly reviewStatus: 'APPROVED' | 'FLAGGED' | string;
  readonly reviewNotes?: string;
}

export interface RevokeBreakGlassRequest {
  readonly revocationReason?: string;
}

export interface ClinicAuditRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly patientId?: string | null;
  readonly actorUserId: string;
  readonly actorUserName: string;
  readonly actorRole: string;
  readonly action: string;
  readonly resourceType: string;
  readonly resourceId: string;
  readonly breakGlassGrantId?: string | null;
  readonly ipAddress?: string | null;
  readonly userAgent?: string | null;
  readonly metadataJson?: string | null;
  readonly tamperHash: string;
  readonly occurredAt: string;
}

export interface AppointmentProductivityRow {
  readonly doctorId: string;
  readonly doctorName: string;
  readonly specialty: string;
  readonly totalAppointments: number;
  readonly completedAppointments: number;
  readonly cancelledAppointments: number;
  readonly noShowAppointments: number;
  readonly completionRatePercent: number;
  readonly totalInvoiced: number;
}

export interface ProcedureUtilizationRow {
  readonly procedureCode: string;
  readonly procedureName: string;
  readonly category: string;
  readonly procedureCount: number;
  readonly totalRevenue: number;
  readonly averagePrice: number;
}

export interface FinancialCollectionSummaryRow {
  readonly currency: string;
  readonly totalInvoiced: number;
  readonly totalCollected: number;
  readonly totalOutstanding: number;
  readonly collectionRatePercent: number;
  readonly invoiceCount: number;
  readonly paymentCount: number;
}

export interface PatientRetentionRecallRow {
  readonly patientId: string;
  readonly patientName: string;
  readonly phone: string;
  readonly lastVisitDate?: string | null;
  readonly daysSinceLastVisit: number;
  readonly recallStatus: 'OVERDUE' | 'DUE_SOON' | 'UP_TO_DATE' | string;
  readonly recommendedProcedure: string;
}

export interface BreakGlassComplianceRow {
  readonly grantId: string;
  readonly requesterName: string;
  readonly patientName: string;
  readonly reason: string;
  readonly grantedAt: string;
  readonly expiresAt: string;
  readonly active: boolean;
  readonly reviewStatus: 'PENDING' | 'APPROVED' | 'FLAGGED' | string;
  readonly reviewerName?: string | null;
  readonly reviewedAt?: string | null;
  readonly reviewNotes?: string | null;
}

export interface ClinicAuditExportView {
  readonly exportType: string;
  readonly generatedAt: string;
  readonly totalRecords: number;
  readonly exportSignature: string;
  readonly records: unknown[];
}

export interface ClinicMedicationPreset {
  readonly id: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly medicationName: string;
  readonly dosage: string;
  readonly frequency: string;
  readonly duration: string;
  readonly instructions?: string | null;
  readonly category: string;
  readonly isFavorite: boolean;
  readonly createdAt?: string;
  readonly updatedAt?: string;
}

export interface CreateMedicationPresetPayload {
  readonly medicationName: string;
  readonly dosage?: string;
  readonly frequency?: string;
  readonly duration?: string;
  readonly instructions?: string | null;
  readonly category?: string;
  readonly isFavorite?: boolean;
  readonly sortOrder?: number;
  readonly projectId?: string;
}

export interface ClinicDiagnosisPreset {
  readonly id: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly name: string;
  readonly code?: string | null;
  readonly sortOrder?: number;
  readonly createdAt?: string;
}

export interface CreateDiagnosisPresetPayload {
  readonly name: string;
  readonly code?: string | null;
  readonly sortOrder?: number;
  readonly projectId?: string;
}

// ---------------------------------------------------------------------------
// Phase 4: lab pipeline, aligner pipeline, feedback and doctor earnings
// ---------------------------------------------------------------------------

/** `EncounterModels.LabOrderRow`, including the Phase 40 impression/teeth/files. */
export interface ClinicLabOrder {
  readonly id: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly patientId: string;
  readonly patientName?: string;
  readonly doctorId?: string | null;
  readonly doctorName?: string | null;
  readonly labName: string;
  readonly workType: string;
  readonly impressionType?: string | null;
  readonly shade?: string | null;
  readonly toothFdi?: number | null;
  readonly teethFdi?: readonly number[];
  readonly files?: readonly string[];
  readonly status: string;
  readonly sentDate?: string;
  readonly dueDate?: string | null;
  readonly receivedDate?: string | null;
  readonly cost?: number;
  readonly currency?: string;
  readonly notes?: string | null;
  readonly createdAt?: string;
  readonly updatedAt?: string;
}

export interface CreateLabOrderPayload {
  readonly patientId: string;
  readonly doctorId?: string | null;
  readonly labName: string;
  readonly workType: string;
  readonly impressionType?: string;
  readonly shade?: string;
  readonly toothFdi?: number | null;
  readonly teethFdi?: readonly number[];
  readonly files?: readonly string[];
  readonly dueDate?: string | null;
  readonly cost?: number;
  readonly currency?: string;
  readonly notes?: string;
  readonly projectId?: string;
}

/** `ClinicOperationsModels.AlignerCaseRow`. */
export interface ClinicAlignerCase {
  readonly id: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly patientId: string;
  readonly patientName?: string;
  readonly doctorId?: string | null;
  readonly doctorName?: string | null;
  readonly caseType: string;
  readonly impressionType?: string | null;
  readonly shade?: string | null;
  readonly status: string;
  readonly upperSteps: number;
  readonly lowerSteps: number;
  readonly teethSummary?: string | null;
  readonly iprSummary?: string | null;
  readonly attachmentsCount: number;
  readonly rxNotes?: string | null;
  readonly files?: readonly string[];
  readonly createdAt?: string;
  readonly updatedAt?: string;
}

export interface CreateAlignerCasePayload {
  readonly patientId: string;
  readonly doctorId?: string | null;
  readonly caseType: string;
  readonly impressionType?: string;
  readonly shade?: string;
  readonly upperSteps?: number;
  readonly lowerSteps?: number;
  readonly teethSummary?: string;
  readonly iprSummary?: string;
  readonly attachmentsCount?: number;
  readonly rxNotes?: string;
  readonly files?: readonly string[];
  readonly projectId?: string;
}

/** `ClinicOperationsModels.FeedbackRow`. */
export interface ClinicFeedback {
  readonly id: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly patientId?: string | null;
  readonly patientName: string;
  readonly patientEmail?: string | null;
  readonly patientPhone?: string | null;
  readonly patientAge?: number | null;
  readonly doctorId?: string | null;
  readonly doctorName?: string | null;
  readonly serviceName?: string | null;
  readonly treatmentsReceived?: string | null;
  readonly clinicLocation?: string | null;
  readonly referralSource?: string | null;
  readonly ratingExplanation: number;
  readonly ratingHygiene: number;
  readonly ratingCourtesy: number;
  readonly ratingComfort: number;
  readonly ratingRecommend: number;
  readonly message?: string | null;
  readonly submittedAt?: string;
  readonly createdAt?: string;
}

export interface CreateFeedbackPayload {
  readonly patientId?: string | null;
  readonly doctorId?: string | null;
  readonly patientName: string;
  readonly patientEmail?: string;
  readonly patientPhone?: string;
  readonly patientAge?: number | null;
  readonly doctorName?: string;
  readonly serviceName?: string;
  readonly treatmentsReceived?: string;
  readonly clinicLocation?: string;
  readonly referralSource?: string;
  readonly ratingExplanation?: number;
  readonly ratingHygiene?: number;
  readonly ratingCourtesy?: number;
  readonly ratingComfort?: number;
  readonly ratingRecommend?: number;
  readonly message?: string;
  readonly projectId?: string;
}

/** `ClinicOperationsModels.DoctorCommissionRow`. */
export interface DoctorCommission {
  readonly doctorId: string;
  readonly doctorName: string;
  readonly specialty?: string | null;
  readonly commissionRate: number;
  readonly appointmentCount: number;
  readonly grossRevenue: number;
  readonly commissionAmount: number;
  readonly paidAmount: number;
  readonly balanceDue: number;
  readonly currency?: string;
}

/** `ClinicOperationsModels.DoctorPayoutRow`. */
export interface DoctorPayout {
  readonly id: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly doctorId: string;
  readonly doctorName?: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly grossAmount: number;
  readonly commissionRate: number;
  readonly commissionAmount: number;
  readonly currency?: string;
  readonly status: string;
  readonly paidAt?: string | null;
  readonly notes?: string | null;
  readonly createdAt?: string;
  readonly updatedAt?: string;
}

export interface CreateDoctorPayoutPayload {
  readonly doctorId: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly notes?: string;
  readonly projectId?: string;
}

// ---------------------------------------------------------------------------
// Five Requirements Expansion Interfaces
// ---------------------------------------------------------------------------

export interface InventoryItemRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicId?: string | null;
  readonly clinicName?: string;
  readonly name: string;
  readonly sku?: string | null;
  readonly category: string;
  readonly unitOfMeasure: string;
  readonly unitCost: number;
  readonly quantityOnHand: number;
  readonly minStockThreshold: number;
  readonly lowStock: boolean;
  readonly isActive: boolean;
  readonly createdAt?: string;
  readonly updatedAt?: string;
}

export interface CreateInventoryItemPayload {
  readonly name: string;
  readonly sku?: string | null;
  readonly category?: string;
  readonly unitOfMeasure?: string;
  readonly unitCost?: number;
  readonly quantityOnHand?: number;
  readonly minStockThreshold?: number;
  readonly clinicId?: string | null;
  readonly recordPurchaseExpense?: boolean;
  readonly projectId?: string;
}

export interface UpdateInventoryItemPayload {
  readonly name?: string;
  readonly sku?: string | null;
  readonly category?: string;
  readonly unitOfMeasure?: string;
  readonly unitCost?: number;
  readonly minStockThreshold?: number;
  readonly clinicId?: string | null;
  readonly isActive?: boolean;
}

export interface AdjustInventoryStockPayload {
  readonly transactionType: 'MANUAL_PURCHASE' | 'MANUAL_ADJUSTMENT' | string;
  readonly quantity: number;
  readonly unitCost?: number;
  readonly notes?: string;
  readonly recordPurchaseExpense?: boolean;
  readonly projectId?: string;
}

export interface ServiceMaterialRow {
  readonly id: string;
  readonly procedureId: string;
  readonly procedureName: string;
  readonly itemId: string;
  readonly itemName: string;
  readonly unitOfMeasure: string;
  readonly unitCost: number;
  readonly quantityRequired: number;
  readonly quantityOnHand: number;
}

export interface ServiceMaterialInput {
  readonly itemId: string;
  readonly quantityRequired: number;
}

export interface InventoryTransactionRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicId?: string | null;
  readonly clinicName?: string;
  readonly itemId: string;
  readonly itemName: string;
  readonly transactionType: string;
  readonly quantity: number;
  readonly quantityBefore: number;
  readonly quantityAfter: number;
  readonly shortfallQuantity: number;
  readonly unitCost: number;
  readonly referenceType?: string | null;
  readonly referenceId?: string | null;
  readonly notes?: string | null;
  readonly createdAt?: string;
}

export interface InventoryStockWarning {
  readonly itemId: string;
  readonly itemName: string;
  readonly quantityRequired: number;
  readonly quantityAvailable: number;
  readonly shortfallQuantity: number;
  readonly message: string;
}

export interface ConsolidatedInventoryRow {
  readonly itemName: string;
  readonly sku?: string | null;
  readonly category: string;
  readonly unitOfMeasure: string;
  readonly totalQuantityOnHand: number;
  readonly branchesCount: number;
  readonly lowStockBranchesCount: number;
  readonly totalValuation: number;
}

export interface StaffScheduleRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicId?: string | null;
  readonly clinicName?: string;
  readonly userId?: string | null;
  readonly doctorId?: string | null;
  readonly staffName: string;
  readonly staffRole: string;
  readonly scheduleType: 'WEEKLY_TEMPLATE' | 'DATE_ROSTER' | string;
  readonly weekday?: number | null;
  readonly shiftDate?: string | null;
  readonly startTime: string;
  readonly endTime: string;
  readonly notes?: string | null;
  readonly isActive: boolean;
  readonly createdAt?: string;
}

export interface CreateStaffSchedulePayload {
  readonly userId?: string | null;
  readonly doctorId?: string | null;
  readonly staffName: string;
  readonly staffRole?: string;
  readonly scheduleType: 'WEEKLY_TEMPLATE' | 'DATE_ROSTER' | string;
  readonly weekday?: number | null;
  readonly shiftDate?: string | null;
  readonly startTime: string;
  readonly endTime: string;
  readonly clinicId?: string | null;
  readonly notes?: string | null;
  readonly projectId?: string;
}

export interface LeaveRequestRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicId?: string | null;
  readonly clinicName?: string;
  readonly userId?: string | null;
  readonly doctorId?: string | null;
  readonly staffName: string;
  readonly staffRole: string;
  readonly leaveType: 'ANNUAL_VACATION' | 'SICK_LEAVE' | 'EMERGENCY_LEAVE' | 'UNPAID_LEAVE' | string;
  readonly startDate: string;
  readonly endDate: string;
  readonly reason?: string | null;
  readonly status: 'PENDING' | 'APPROVED' | 'REJECTED' | string;
  readonly reviewedByUserId?: string | null;
  readonly reviewedByName?: string | null;
  readonly reviewedAt?: string | null;
  readonly reviewNotes?: string | null;
  readonly createdAt?: string;
}

export interface CreateLeaveRequestPayload {
  readonly userId?: string | null;
  readonly doctorId?: string | null;
  readonly staffName?: string;
  readonly staffRole?: string;
  readonly leaveType: string;
  readonly startDate: string;
  readonly endDate: string;
  readonly clinicId?: string | null;
  readonly reason?: string;
  readonly projectId?: string;
}

export interface ReviewLeaveRequestPayload {
  readonly status: 'APPROVED' | 'REJECTED';
  readonly reviewNotes?: string;
}

export interface LeaveConflictReport {
  readonly impactedAppointmentsCount: number;
  readonly impactedShiftsCount: number;
  readonly impactedDetails: readonly string[];
  readonly warningMessage?: string | null;
}

export interface ClinicExpenseRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicId?: string | null;
  readonly clinicName?: string;
  readonly category: string;
  readonly title: string;
  readonly amount: number;
  readonly currency: string;
  readonly expenseFrequency: string;
  readonly paymentDate: string;
  readonly templateId?: string | null;
  readonly notes?: string | null;
  readonly createdAt?: string;
}

export interface CreateClinicExpensePayload {
  readonly category: string;
  readonly title: string;
  readonly amount: number;
  readonly expenseFrequency?: string;
  readonly paymentDate?: string;
  readonly clinicId?: string | null;
  readonly notes?: string;
  readonly projectId?: string;
}

export interface RecurringExpenseTemplateRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicId?: string | null;
  readonly clinicName?: string;
  readonly category: string;
  readonly title: string;
  readonly defaultAmount: number;
  readonly dayOfMonth: number;
  readonly lastConfirmedMonth?: string | null;
  readonly dueThisMonth: boolean;
  readonly notes?: string | null;
  readonly isActive: boolean;
}

export interface CreateRecurringTemplatePayload {
  readonly category: string;
  readonly title: string;
  readonly defaultAmount: number;
  readonly dayOfMonth?: number;
  readonly clinicId?: string | null;
  readonly notes?: string;
  readonly projectId?: string;
}

export interface ConfirmRecurringTemplatePayload {
  readonly amount?: number;
  readonly paymentDate?: string;
  readonly monthKey?: string;
  readonly notes?: string;
}

export interface PeriodFinancialBucket {
  readonly periodKey: string;
  readonly periodLabel: string;
  readonly totalRevenue: number;
  readonly operationalExpenses: number;
  readonly doctorPayouts: number;
  readonly totalExpenses: number;
  readonly netProfit: number;
}

export interface BranchFinancialBreakdown {
  readonly clinicId?: string | null;
  readonly branchName: string;
  readonly totalRevenue: number;
  readonly totalExpenses: number;
  readonly netProfit: number;
}

export interface FinancialAnalyticsReport {
  readonly year: number;
  readonly periodType: string;
  readonly totalRevenue: number;
  readonly operationalExpenses: number;
  readonly doctorPayouts: number;
  readonly totalExpenses: number;
  readonly netProfit: number;
  readonly expensesByCategory: Record<string, number>;
  readonly quarterlyBreakdown: readonly PeriodFinancialBucket[];
  readonly monthlyBreakdown: readonly PeriodFinancialBucket[];
  readonly branchBreakdown: readonly BranchFinancialBreakdown[];
}

export interface TreatmentRoomRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicId?: string | null;
  readonly clinicName?: string;
  readonly name: string;
  readonly roomNumber?: string | null;
  readonly roomType: string;
  readonly assignedDoctorId?: string | null;
  readonly assignedDoctorName?: string | null;
  readonly operationalStartHour: string;
  readonly operationalEndHour: string;
  readonly isActive: boolean;
  readonly currentStatus: 'AVAILABLE' | 'OCCUPIED' | 'INACTIVE' | string;
  readonly currentOccupiedByDoctor?: string | null;
  readonly totalAppointmentsCount: number;
  readonly completedProceduresCount: number;
  readonly bookedMinutes: number;
  readonly utilizationPercent: number;
  readonly roomRevenue: number;
}

export interface CreateTreatmentRoomPayload {
  readonly name: string;
  readonly roomNumber?: string | null;
  readonly roomType?: string;
  readonly clinicId?: string | null;
  readonly assignedDoctorId?: string | null;
  readonly operationalStartHour?: string;
  readonly operationalEndHour?: string;
  readonly isActive?: boolean;
  readonly projectId?: string;
}

export interface ImagingLabRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicId?: string | null;
  readonly clinicName?: string;
  readonly name: string;
  readonly contactPhone?: string | null;
  readonly contactEmail?: string | null;
  readonly address?: string | null;
  readonly integrationMode: 'MANUAL' | 'DIRECT_CLOUD_API' | string;
  readonly apiEndpointUrl?: string | null;
  readonly notes?: string | null;
  readonly isActive: boolean;
}

export interface CreateImagingLabPayload {
  readonly name: string;
  readonly contactPhone?: string | null;
  readonly contactEmail?: string | null;
  readonly address?: string | null;
  readonly integrationMode?: string;
  readonly apiEndpointUrl?: string | null;
  readonly clinicId?: string | null;
  readonly notes?: string | null;
  readonly projectId?: string;
}

export interface ImagingRequestRow {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicId?: string | null;
  readonly clinicName?: string;
  readonly patientId: string;
  readonly patientName?: string;
  readonly doctorId?: string | null;
  readonly doctorName?: string | null;
  readonly labId?: string | null;
  readonly labName?: string | null;
  readonly imagingType: string;
  readonly proceduresCount: number;
  readonly toothFdi?: number | null;
  readonly teethSummary?: string | null;
  readonly clinicalIndication?: string | null;
  readonly status: string;
  readonly requestDate: string;
  readonly completionDate?: string | null;
  readonly costToClinic: number;
  readonly amountChargedToPatient: number;
  readonly netProfit: number;
  readonly invoiceId?: string | null;
  readonly expenseId?: string | null;
  readonly cloudStorageUrl?: string | null;
  readonly relatedFiles?: readonly string[];
  readonly findingsNotes?: string | null;
  readonly createdAt?: string;
}

export interface CreateImagingRequestPayload {
  readonly patientId: string;
  readonly doctorId?: string | null;
  readonly labId?: string | null;
  readonly labName?: string | null;
  readonly imagingType: string;
  readonly proceduresCount?: number;
  readonly toothFdi?: number | null;
  readonly teethSummary?: string | null;
  readonly clinicalIndication?: string | null;
  readonly status?: string;
  readonly requestDate?: string;
  readonly completionDate?: string | null;
  readonly costToClinic?: number;
  readonly amountChargedToPatient?: number;
  readonly cloudStorageUrl?: string | null;
  readonly relatedFiles?: readonly string[];
  readonly findingsNotes?: string | null;
  readonly clinicId?: string | null;
  readonly projectId?: string;
}

export interface UpdateImagingRequestPayload {
  readonly status?: string;
  readonly completionDate?: string | null;
  readonly costToClinic?: number;
  readonly amountChargedToPatient?: number;
  readonly cloudStorageUrl?: string | null;
  readonly relatedFiles?: readonly string[];
  readonly findingsNotes?: string | null;
  readonly labId?: string | null;
  readonly labName?: string | null;
}

export interface ImagingSummaryAnalytics {
  readonly totalRequests: number;
  readonly totalProceduresCount: number;
  readonly totalCostToClinic: number;
  readonly totalChargedToPatient: number;
  readonly netProfit: number;
}
