import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  CLINIC_PERMISSION_CATALOG,
  ClinicRbacService,
  DEFAULT_ROLE_PERMISSIONS,
  IfPermissionComponent,
  canShowAppointmentEditAction,
  computeEffectivePermissionsForRoles,
  evaluateUiVisibility,
  resolvePermissionLandingPage,
  resolveVisibleNavSections,
} from './clinic-rbac';

@Component({
  standalone: true,
  imports: [IfPermissionComponent],
  template: `
    <fe-if-permission permission="discounts.apply">
      <button id="apply-discount-btn">Apply Discount</button>
    </fe-if-permission>
    <fe-if-permission permission="invoices.cancel">
      <button id="cancel-invoice-btn">Cancel Invoice</button>
    </fe-if-permission>
    <fe-if-permission permission="clinical.write_record">
      <button id="edit-dental-chart-btn">Edit Dental Chart</button>
    </fe-if-permission>
    <fe-if-permission permission="clinic.settings.manage">
      <button id="clinic-settings-btn">Clinic Settings</button>
    </fe-if-permission>
  `,
})
class TestPermissionHostComponent {}

describe('Clinic Multi-Role RBAC & Permission-Driven UI Visibility', () => {
  let rbacService: ClinicRbacService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [TestPermissionHostComponent, IfPermissionComponent],
    });
    rbacService = TestBed.inject(ClinicRbacService);
  });

  it('defines all 25 canonical permission keys and default role mappings from the specification', () => {
    expect(Object.keys(CLINIC_PERMISSION_CATALOG).length).toBe(25);
    expect(DEFAULT_ROLE_PERMISSIONS.platform_admin.length).toBe(2);
    expect(DEFAULT_ROLE_PERMISSIONS.clinic_admin.length).toBe(15);
    expect(DEFAULT_ROLE_PERMISSIONS.receptionist.length).toBe(9);
    expect(DEFAULT_ROLE_PERMISSIONS.doctor.length).toBe(9);
    expect(DEFAULT_ROLE_PERMISSIONS.accountant.length).toBe(9);
  });

  it('enforces Platform Admin UI visibility: sees only platform tenant management, zero clinic medical/financial UI', () => {
    const perms = computeEffectivePermissionsForRoles(['platform_admin']);
    const vis = evaluateUiVisibility(perms);

    expect(vis.visibleSectionIds).toEqual(['platform']);
    expect(vis.visibleNavItemIds).toEqual(['clinics', 'subscriptions']);
    expect(vis.showMedicalHistorySection).toBe(false);
    expect(vis.showDiagnosisAndClinicalRecord).toBe(false);
    expect(vis.showWriteMedicalRecordControls).toBe(false);
    expect(vis.showCreateInvoiceButton).toBe(false);
    expect(vis.showCancelInvoiceButton).toBe(false);
    expect(vis.showApplyDiscountButton).toBe(false);
    expect(vis.showClinicSettings).toBe(false);
    expect(resolvePermissionLandingPage(perms)).toBe('/app/clinic/branches');
  });

  it('enforces Clinic Admin default UI visibility: sees all operational/billing/settings UI, hides medical records by default unless explicitly granted', () => {
    const defaultPerms = computeEffectivePermissionsForRoles(['clinic_admin']);
    const vis = evaluateUiVisibility(defaultPerms);

    // Present for clinic_admin:
    expect(vis.showClinicSettings).toBe(true);
    expect(vis.showUsersAndRoles).toBe(true);
    expect(vis.showCreateInvoiceButton).toBe(true);
    expect(vis.showCancelInvoiceButton).toBe(true);
    expect(vis.showApplyDiscountButton).toBe(true);
    expect(vis.showFinancialReports).toBe(true);
    expect(vis.showOperationalReports).toBe(true);
    expect(vis.showTreatmentPlanFinancials).toBe(true);

    // Absent by default for clinic_admin (privacy rule):
    expect(vis.showMedicalHistorySection).toBe(false);
    expect(vis.showDiagnosisAndClinicalRecord).toBe(false);
    expect(vis.showWriteMedicalRecordControls).toBe(false);
    expect(vis.showTreatmentPlanClinicalDetails).toBe(false);
    expect(vis.showEditTreatmentPlanButton).toBe(false);

    expect(vis.visibleNavItemIds).toContain('calendar');
    expect(vis.visibleNavItemIds).toContain('patients');
    expect(vis.visibleNavItemIds).toContain('invoices');
    expect(vis.visibleNavItemIds).toContain('operational_reports');
    expect(vis.visibleNavItemIds).toContain('financial_reports');
    expect(vis.visibleNavItemIds).toContain('clinic_settings');
    expect(vis.visibleNavItemIds).toContain('users_and_roles');
    expect(vis.visibleNavItemIds).not.toContain('medical_record');
    expect(resolvePermissionLandingPage(defaultPerms)).toBe('/app/clinic/overview');

    // Explicit audited clinical grant enables clinical UI:
    const grantedPerms = computeEffectivePermissionsForRoles(
      ['clinic_admin', 'audited_clinical_grant'],
      {
        audited_clinical_grant: [
          CLINIC_PERMISSION_CATALOG.clinicalViewHistory,
          CLINIC_PERMISSION_CATALOG.clinicalViewRecord,
        ],
      },
    );
    const grantedVis = evaluateUiVisibility(grantedPerms);
    expect(grantedVis.showMedicalHistorySection).toBe(true);
    expect(grantedVis.showDiagnosisAndClinicalRecord).toBe(true);
    expect(grantedVis.visibleNavItemIds).toContain('medical_record');
  });

  it('enforces Receptionist UI visibility: hides discounts, invoice cancellation, clinical chart/notes, financial reports, and settings', () => {
    const perms = computeEffectivePermissionsForRoles(['receptionist']);
    const vis = evaluateUiVisibility(perms);

    // Allowed receptionist actions:
    expect(vis.showPatientAdminEditButton).toBe(true);
    expect(vis.showTreatmentPlanStatus).toBe(true);
    expect(vis.showCreateInvoiceButton).toBe(true);
    expect(vis.showRecordPaymentButton).toBe(true);
    expect(vis.showOperationalReports).toBe(true);

    // Hidden ("Hide, don't disable"):
    expect(vis.showApplyDiscountButton).toBe(false);
    expect(vis.showCancelInvoiceButton).toBe(false);
    expect(vis.showMedicalHistorySection).toBe(false);
    expect(vis.showDiagnosisAndClinicalRecord).toBe(false);
    expect(vis.showWriteMedicalRecordControls).toBe(false);
    expect(vis.showTreatmentPlanFinancials).toBe(false);
    expect(vis.showTreatmentPlanClinicalDetails).toBe(false);
    expect(vis.showEditTreatmentPlanButton).toBe(false);
    expect(vis.showFinancialReports).toBe(false);
    expect(vis.showClinicSettings).toBe(false);
    expect(vis.showUsersAndRoles).toBe(false);

    expect(vis.visibleNavItemIds).toContain('calendar');
    expect(vis.visibleNavItemIds).toContain('patients');
    expect(vis.visibleNavItemIds).toContain('invoices');
    expect(vis.visibleNavItemIds).toContain('operational_reports');
    expect(vis.visibleNavItemIds).not.toContain('medical_record');
    expect(vis.visibleNavItemIds).not.toContain('financial_reports');
    expect(vis.visibleNavItemIds).not.toContain('clinic_settings');
    expect(vis.visibleSectionIds).not.toContain('administration');
    expect(resolvePermissionLandingPage(perms)).toBe('/app/clinic/calendar');
  });

  it('enforces Doctor UI visibility and assigned-patient scope (doctor_sees_all_patients OFF vs ON)', () => {
    const perms = computeEffectivePermissionsForRoles(['doctor']);

    // 1. Assigned patient with doctorSeesAllPatients = false: sees clinical tabs & treatment plan controls
    const assignedVis = evaluateUiVisibility(perms, {
      isAssignedToCurrentDoctor: true,
      doctorSeesAllPatients: false,
    });
    expect(assignedVis.showMedicalHistorySection).toBe(true);
    expect(assignedVis.showDiagnosisAndClinicalRecord).toBe(true);
    expect(assignedVis.showWriteMedicalRecordControls).toBe(true);
    expect(assignedVis.showTreatmentPlanClinicalDetails).toBe(true);
    expect(assignedVis.showEditTreatmentPlanButton).toBe(true);

    // Hidden for doctor regardless of patient scope:
    expect(assignedVis.showPatientAdminEditButton).toBe(false);
    expect(assignedVis.showCreateInvoiceButton).toBe(false);
    expect(assignedVis.showRecordPaymentButton).toBe(false);
    expect(assignedVis.showApplyDiscountButton).toBe(false);
    expect(assignedVis.showCancelInvoiceButton).toBe(false);
    expect(assignedVis.showClinicSettings).toBe(false);
    expect(assignedVis.showFinancialReports).toBe(false);

    // 2. Unassigned patient with doctorSeesAllPatients = false: clinical tabs & controls are hidden
    const unassignedVis = evaluateUiVisibility(perms, {
      isAssignedToCurrentDoctor: false,
      doctorSeesAllPatients: false,
    });
    expect(unassignedVis.showMedicalHistorySection).toBe(false);
    expect(unassignedVis.showDiagnosisAndClinicalRecord).toBe(false);
    expect(unassignedVis.showWriteMedicalRecordControls).toBe(false);
    expect(unassignedVis.showTreatmentPlanClinicalDetails).toBe(false);
    expect(unassignedVis.showEditTreatmentPlanButton).toBe(false);

    // 3. Unassigned patient with doctorSeesAllPatients = true: clinical tabs & controls are shown
    const allClinicPatientsVis = evaluateUiVisibility(perms, {
      isAssignedToCurrentDoctor: false,
      doctorSeesAllPatients: true,
    });
    expect(allClinicPatientsVis.showMedicalHistorySection).toBe(true);
    expect(allClinicPatientsVis.showDiagnosisAndClinicalRecord).toBe(true);
    expect(allClinicPatientsVis.showWriteMedicalRecordControls).toBe(true);
    expect(allClinicPatientsVis.showTreatmentPlanClinicalDetails).toBe(true);
    expect(allClinicPatientsVis.showEditTreatmentPlanButton).toBe(true);

    // Contextual row action check for appointments:
    expect(canShowAppointmentEditAction(perms, 'doc-1', 'doc-1')).toBe(true);
    expect(canShowAppointmentEditAction(perms, 'doc-2', 'doc-1')).toBe(false);
    expect(resolvePermissionLandingPage(perms)).toBe('/app/clinic/calendar');
  });

  it('enforces Accountant UI visibility: sees billing, payments, discounts, financial reports; hides all clinical and settings elements', () => {
    const perms = computeEffectivePermissionsForRoles(['accountant']);
    const vis = evaluateUiVisibility(perms);

    // Present for accountant:
    expect(vis.showCreateInvoiceButton).toBe(true);
    expect(vis.showCancelInvoiceButton).toBe(true);
    expect(vis.showRecordPaymentButton).toBe(true);
    expect(vis.showApplyDiscountButton).toBe(true);
    expect(vis.showFinancialReports).toBe(true);
    expect(vis.showTreatmentPlanFinancials).toBe(true);

    // Hidden for accountant:
    expect(vis.showPatientAdminEditButton).toBe(false);
    expect(vis.showMedicalHistorySection).toBe(false);
    expect(vis.showDiagnosisAndClinicalRecord).toBe(false);
    expect(vis.showWriteMedicalRecordControls).toBe(false);
    expect(vis.showTreatmentPlanClinicalDetails).toBe(false);
    expect(vis.showEditTreatmentPlanButton).toBe(false);
    expect(vis.showClinicSettings).toBe(false);
    expect(vis.showUsersAndRoles).toBe(false);

    expect(vis.visibleNavItemIds).toContain('invoices');
    expect(vis.visibleNavItemIds).toContain('financial_reports');
    expect(vis.visibleNavItemIds).not.toContain('medical_record');
    expect(vis.visibleNavItemIds).not.toContain('clinic_settings');
    expect(vis.visibleSectionIds).not.toContain('clinical');
    expect(vis.visibleSectionIds).not.toContain('administration');
    expect(resolvePermissionLandingPage(perms)).toBe('/app/clinic/invoices');
  });

  it('computes the union of permissions for a multi-role user (clinic_admin + doctor)', () => {
    const unionPerms = computeEffectivePermissionsForRoles(['clinic_admin', 'doctor']);
    const vis = evaluateUiVisibility(unionPerms);

    // Union grants both administrative/billing and full clinical permissions:
    expect(vis.showClinicSettings).toBe(true);
    expect(vis.showUsersAndRoles).toBe(true);
    expect(vis.showApplyDiscountButton).toBe(true);
    expect(vis.showCancelInvoiceButton).toBe(true);
    expect(vis.showMedicalHistorySection).toBe(true);
    expect(vis.showDiagnosisAndClinicalRecord).toBe(true);
    expect(vis.showWriteMedicalRecordControls).toBe(true);
    expect(vis.showTreatmentPlanClinicalDetails).toBe(true);
    expect(vis.showEditTreatmentPlanButton).toBe(true);

    expect(unionPerms.has(CLINIC_PERMISSION_CATALOG.clinicSettingsManage)).toBe(true);
    expect(unionPerms.has(CLINIC_PERMISSION_CATALOG.clinicalWriteRecord)).toBe(true);
    expect(resolvePermissionLandingPage(unionPerms)).toBe('/app/clinic/overview');
  });

  it('removes unauthorized buttons from the DOM completely via <fe-if-permission> ("Hide, don\'t disable")', () => {
    const fixture: ComponentFixture<TestPermissionHostComponent> = TestBed.createComponent(
      TestPermissionHostComponent,
    );

    // 1. Receptionist: none of discounts.apply, invoices.cancel, clinical.write_record, or clinic.settings.manage exist in DOM
    rbacService.setAssignedRoles(['receptionist']);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('#apply-discount-btn')).toBeNull();
    expect(el.querySelector('#cancel-invoice-btn')).toBeNull();
    expect(el.querySelector('#edit-dental-chart-btn')).toBeNull();
    expect(el.querySelector('#clinic-settings-btn')).toBeNull();

    // 2. Accountant: discounts.apply and invoices.cancel are rendered; clinical and settings are absent
    rbacService.setAssignedRoles(['accountant']);
    fixture.detectChanges();
    expect(el.querySelector('#apply-discount-btn')).not.toBeNull();
    expect(el.querySelector('#cancel-invoice-btn')).not.toBeNull();
    expect(el.querySelector('#edit-dental-chart-btn')).toBeNull();
    expect(el.querySelector('#clinic-settings-btn')).toBeNull();

    // 3. Doctor: only edit-dental-chart-btn is rendered in DOM
    rbacService.setAssignedRoles(['doctor']);
    fixture.detectChanges();
    expect(el.querySelector('#apply-discount-btn')).toBeNull();
    expect(el.querySelector('#cancel-invoice-btn')).toBeNull();
    expect(el.querySelector('#edit-dental-chart-btn')).not.toBeNull();
    expect(el.querySelector('#clinic-settings-btn')).toBeNull();

    // 4. Multi-role (clinic_admin + doctor): all 4 buttons are rendered in DOM
    rbacService.setAssignedRoles(['clinic_admin', 'doctor']);
    fixture.detectChanges();
    expect(el.querySelector('#apply-discount-btn')).not.toBeNull();
    expect(el.querySelector('#cancel-invoice-btn')).not.toBeNull();
    expect(el.querySelector('#edit-dental-chart-btn')).not.toBeNull();
    expect(el.querySelector('#clinic-settings-btn')).not.toBeNull();
  });

  it('filters sidebar sections so a section is rendered only when at least one item inside it is permitted', () => {
    const receptionistPermissions = computeEffectivePermissionsForRoles(['receptionist']);
    const sections = resolveVisibleNavSections(receptionistPermissions);

    expect(sections.some((s) => s.sectionId === 'administration')).toBe(false);
    expect(sections.some((s) => s.sectionId === 'clinical')).toBe(false);
    expect(sections.some((s) => s.sectionId === 'scheduling_and_patients')).toBe(true);
  });
});
