package com.firstelement.crm.clinic.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.CrmFixture;
import com.firstelement.crm.CrmIntegrationTestBase;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.patient.PatientService;
import com.firstelement.crm.clinic.treatment.TreatmentPlanModels;
import com.firstelement.crm.clinic.treatment.TreatmentPlanService;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.query.TenantQueryGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for Dental Clinic Billing, Refunds, Idempotency, and Append-Only Ledger (Slice S6).
 *
 * <p>Validates invoice generation from approved treatment plans, idempotent payment submissions,
 * refunds/reversals with balance recalculation, append-only dual-entry patient ledger reconciliation,
 * and strict multi-tenant isolation.
 */
@SpringBootTest(classes = com.firstelement.crm.CrmTestApplication.class)
class InvoiceServiceIntegrationTest extends CrmIntegrationTestBase {

    @Autowired
    private InvoiceService service;

    @Autowired
    private TreatmentPlanService planService;

    @Autowired
    private PatientService patients;

    @Autowired
    private CrmFixture fixture;

    @Autowired
    private TenantQueryGuard guard;

    @BeforeEach
    void resetDatabase() {
        fixture.reset();
    }

    private ClinicScope tenantAScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminAContext(), fixture.projectA1);
    }

    private ClinicScope tenantBScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminBContext(), fixture.projectB1);
    }

    private UUID newPatient(ClinicScope scope, String patientNo) {
        return patients.create(scope, patientNo, "Billing Test Patient", "Billing", "Patient", null,
                "+96279" + Math.abs(patientNo.hashCode() % 10000000), null, "ar", null).id();
    }

    @Test
    @DisplayName("generates invoice from approved treatment plan items and posts initial ledger charge")
    void generatesInvoiceFromApprovedPlanAndPostsLedgerCharge() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-BIL-01");

        // Create treatment plan and items
        UUID planId = planService.createPlan(scope, patientId, new TreatmentPlanModels.CreatePlanRequest(
                "Ortho & Restorative Plan",
                "Phased treatment",
                fixture.projectA1.toString(),
                "PRIMARY",
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("450.000")
        ));

        UUID item1 = planService.addItem(scope, planId, new TreatmentPlanModels.CreatePlanItemRequest(
                1, "PHASE_1", 1, null, "Composite Restoration", 21, null,
                new BigDecimal("150.000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("150.000"), "Class IV"
        ));

        UUID item2 = planService.addItem(scope, planId, new TreatmentPlanModels.CreatePlanItemRequest(
                2, "PHASE_1", 1, null, "Porcelain Veneer", 22, null,
                new BigDecimal("300.000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300.000"), "Lab veneer"
        ));

        // Approve the plan
        planService.approvePlan(scope, planId, fixture.adminAContext().userId(), new TreatmentPlanModels.ApprovePlanRequest(
                "Patient consented in clinic"
        ));

        // Generate invoice from plan
        InvoiceModels.InvoiceHeader invoice = service.generateInvoiceFromPlan(scope,
                new InvoiceModels.GenerateInvoiceFromPlanRequest(
                        patientId,
                        planId,
                        List.of(item1, item2),
                        "JOD",
                        "Invoice for Phase 1 procedures"
                ), fixture.adminAContext());

        assertThat(invoice).isNotNull();
        assertThat(invoice.invoiceNumber()).startsWith("INV-");
        assertThat(invoice.subtotal()).isEqualByComparingTo("450.000");
        assertThat(invoice.totalAmount()).isEqualByComparingTo("450.000");
        assertThat(invoice.balanceDue()).isEqualByComparingTo("450.000");
        assertThat(invoice.status()).isEqualTo("ISSUED");

        // Check patient ledger
        List<InvoiceModels.PatientLedgerEntryRow> ledger = service.getPatientLedger(scope, patientId, null, null);
        assertThat(ledger).hasSize(1);
        InvoiceModels.PatientLedgerEntryRow entry = ledger.get(0);
        assertThat(entry.entryType()).isEqualTo("INVOICE_CHARGE");
        assertThat(entry.direction()).isEqualTo("DEBIT");
        assertThat(entry.amount()).isEqualByComparingTo("450.000");
        assertThat(entry.invoiceId()).isEqualTo(invoice.id());
    }

    @Test
    @DisplayName("records idempotent payment preventing duplicate charges")
    void recordsIdempotentPaymentWithoutDuplicateCharges() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-BIL-02");

        // Create direct invoice
        InvoiceModels.InvoiceHeader invoice = service.createInvoice(scope, new InvoiceModels.CreateInvoiceRequest(
                patientId,
                null,
                null,
                "JOD",
                "Direct cleaning invoice",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(
                        null, null, "PROCEDURE", "Scaling & Polishing", null, new BigDecimal("80.000"), 1
                ))
        ), fixture.adminAContext());

        String idempotencyKey = "IDEM-PAY-TEST-998877";

        // First payment call
        InvoiceModels.PaymentReceipt receipt1 = service.recordPayment(scope, invoice.id(),
                new InvoiceModels.RecordPaymentRequest(
                        new BigDecimal("40.000"),
                        "JOD",
                        "CARD",
                        "AUTH-CARD-1234",
                        "First partial installment",
                        idempotencyKey
                ), fixture.adminAContext());

        assertThat(receipt1).isNotNull();
        assertThat(receipt1.amount()).isEqualByComparingTo("40.000");
        assertThat(receipt1.remainingInvoiceBalance()).isEqualByComparingTo("40.000");

        // Second payment call with the EXACT SAME idempotency key (e.g. network retry)
        InvoiceModels.PaymentReceipt receipt2 = service.recordPayment(scope, invoice.id(),
                new InvoiceModels.RecordPaymentRequest(
                        new BigDecimal("40.000"),
                        "JOD",
                        "CARD",
                        "AUTH-CARD-1234",
                        "First partial installment",
                        idempotencyKey
                ), fixture.adminAContext());

        // Must return the exact same receipt without creating a new payment
        assertThat(receipt2.paymentId()).isEqualTo(receipt1.paymentId());
        assertThat(receipt2.paymentNumber()).isEqualTo(receipt1.paymentNumber());

        // Verify invoice totals: only one payment of 40.000 applied
        InvoiceModels.InvoiceDetail detail = service.getInvoiceDetail(scope, invoice.id());
        assertThat(detail.invoice().paidAmount()).isEqualByComparingTo("40.000");
        assertThat(detail.invoice().balanceDue()).isEqualByComparingTo("40.000");
        assertThat(detail.payments()).hasSize(1);
    }

    @Test
    @DisplayName("records refund and recalculates invoice balance and posts refund debit to ledger")
    void recordsRefundAndRecalculatesBalancesAndUpdatesLedger() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-BIL-03");

        InvoiceModels.InvoiceHeader invoice = service.createInvoice(scope, new InvoiceModels.CreateInvoiceRequest(
                patientId,
                null,
                null,
                "JOD",
                "Crown invoice",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(
                        null, null, "PROCEDURE", "Ceramic Crown", 11, new BigDecimal("200.000"), 1
                ))
        ), fixture.adminAContext());

        // Pay full amount
        InvoiceModels.PaymentReceipt payment = service.recordPayment(scope, invoice.id(),
                new InvoiceModels.RecordPaymentRequest(
                        new BigDecimal("200.000"), "JOD", "CASH", "REC-01", "Full cash payment"
                ), fixture.adminAContext());

        InvoiceModels.InvoiceDetail afterPay = service.getInvoiceDetail(scope, invoice.id());
        assertThat(afterPay.invoice().status()).isEqualTo("PAID");
        assertThat(afterPay.invoice().balanceDue()).isEqualByComparingTo("0.000");

        // Issue refund of 50.000 JOD
        InvoiceModels.RefundRow refund = service.recordRefund(scope, invoice.id(),
                new InvoiceModels.RecordRefundRequest(
                        new BigDecimal("50.000"),
                        "JOD",
                        "CASH",
                        "Patient requested partial refund for rescheduled appointment",
                        payment.paymentId()
                ), fixture.adminAContext());

        assertThat(refund).isNotNull();
        assertThat(refund.refundNumber()).startsWith("REF-");
        assertThat(refund.amount()).isEqualByComparingTo("50.000");

        // Invoice totals must be recalculated: paidAmount is now 150.000, balanceDue is 50.000, status PARTIALLY_PAID
        InvoiceModels.InvoiceDetail afterRefund = service.getInvoiceDetail(scope, invoice.id());
        assertThat(afterRefund.invoice().paidAmount()).isEqualByComparingTo("150.000");
        assertThat(afterRefund.invoice().balanceDue()).isEqualByComparingTo("50.000");
        assertThat(afterRefund.invoice().status()).isEqualTo("PARTIALLY_PAID");

        // Verify ledger entries
        List<InvoiceModels.PatientLedgerEntryRow> ledger = service.getPatientLedger(scope, patientId, null, null);
        assertThat(ledger).hasSize(3); // INVOICE_CHARGE, PAYMENT_CREDIT, REFUND_DEBIT

        InvoiceModels.PatientLedgerEntryRow refundEntry = ledger.stream()
                .filter(e -> "REFUND_DEBIT".equals(e.entryType()))
                .findFirst()
                .orElseThrow();
        assertThat(refundEntry.direction()).isEqualTo("DEBIT");
        assertThat(refundEntry.amount()).isEqualByComparingTo("50.000");
    }

    @Test
    @DisplayName("reconciles patient append-only ledger and generates consolidated statement of account")
    void reconcilesPatientLedgerAndGeneratesStatement() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-BIL-04");

        // Invoice 1: 100.000 JOD
        InvoiceModels.InvoiceHeader inv1 = service.createInvoice(scope, new InvoiceModels.CreateInvoiceRequest(
                patientId, null, null, "JOD", "Inv 1",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(null, null, "PROCEDURE", "Filling", null, new BigDecimal("100.000"), 1))
        ), fixture.adminAContext());

        // Apply discount: 10.000 JOD
        service.applyDiscount(scope, inv1.id(), new InvoiceModels.ApplyDiscountRequest(
                new BigDecimal("10.000"), "Family member discount"
        ), fixture.adminAContext());

        // Payment: 50.000 JOD
        service.recordPayment(scope, inv1.id(), new InvoiceModels.RecordPaymentRequest(
                new BigDecimal("50.000"), "JOD", "CASH", "REC-1", "Cash payment"
        ), fixture.adminAContext());

        // Invoice 2: 80.000 JOD
        InvoiceModels.InvoiceHeader inv2 = service.createInvoice(scope, new InvoiceModels.CreateInvoiceRequest(
                patientId, null, null, "JOD", "Inv 2",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(null, null, "PROCEDURE", "Extraction", null, new BigDecimal("80.000"), 1))
        ), fixture.adminAContext());

        // Payment: 80.000 JOD
        InvoiceModels.PaymentReceipt pmt2 = service.recordPayment(scope, inv2.id(), new InvoiceModels.RecordPaymentRequest(
                new BigDecimal("80.000"), "JOD", "CARD", "REC-2", "Card payment"
        ), fixture.adminAContext());

        // Refund: 20.000 JOD on Invoice 2
        service.recordRefund(scope, inv2.id(), new InvoiceModels.RecordRefundRequest(
                new BigDecimal("20.000"), "JOD", "CARD", "Partial refund", pmt2.paymentId()
        ), fixture.adminAContext());

        // Generate patient statement
        InvoiceModels.PatientStatement statement = service.getPatientStatement(scope, patientId, null, null);

        assertThat(statement).isNotNull();
        assertThat(statement.patientId()).isEqualTo(patientId);
        assertThat(statement.currency()).isEqualTo("JOD");

        // Expected Debits: Inv1 (100) + Inv2 (80) + Refund (20) = 200.000 JOD
        assertThat(statement.totalDebits()).isEqualByComparingTo("200.000");

        // Expected Credits: Discount (10) + Pmt1 (50) + Pmt2 (80) = 140.000 JOD
        assertThat(statement.totalCredits()).isEqualByComparingTo("140.000");

        // Net balance due: 200.000 - 140.000 = 60.000 JOD
        assertThat(statement.netBalanceDue()).isEqualByComparingTo("60.000");

        // The statement entries must contain all 6 transactions
        assertThat(statement.entries()).hasSize(6);
    }

    @Test
    @DisplayName("enforces multi-tenant isolation across invoices, payments, refunds, and ledger")
    void enforcesMultiTenantIsolation() {
        ClinicScope scopeA = tenantAScope();
        ClinicScope scopeB = tenantBScope();

        UUID patientA = newPatient(scopeA, "P-BIL-TENA");
        UUID patientB = newPatient(scopeB, "P-BIL-TENB");

        InvoiceModels.InvoiceHeader invA = service.createInvoice(scopeA, new InvoiceModels.CreateInvoiceRequest(
                patientA, null, null, "JOD", "Tenant A Invoice",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(null, null, "PROCEDURE", "Service A", null, new BigDecimal("100.000"), 1))
        ), fixture.adminAContext());

        // Tenant B cannot view Tenant A's invoice
        assertThatThrownBy(() -> service.getInvoiceDetail(scopeB, invA.id()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Tenant B cannot record payment on Tenant A's invoice
        assertThatThrownBy(() -> service.recordPayment(scopeB, invA.id(), new InvoiceModels.RecordPaymentRequest(
                new BigDecimal("50.000"), "JOD", "CASH", "T-B-PAY", "Cross tenant attempt"
        ), fixture.adminBContext()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Tenant B cannot record refund on Tenant A's invoice
        assertThatThrownBy(() -> service.recordRefund(scopeB, invA.id(), new InvoiceModels.RecordRefundRequest(
                new BigDecimal("10.000"), "JOD", "CASH", "Cross tenant refund", null
        ), fixture.adminBContext()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Tenant B cannot read Tenant A's patient ledger or statement
        assertThat(service.getPatientLedger(scopeB, patientA, null, null)).isEmpty();
    }

    @Test
    @DisplayName("refuses refund exceeding paid amount on invoice")
    void refusesRefundExceedingPaidAmount() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-BIL-REFUND-LIMIT");

        InvoiceModels.InvoiceHeader invoice = service.createInvoice(scope, new InvoiceModels.CreateInvoiceRequest(
                patientId, null, null, "JOD", "Checkup",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(null, null, "PROCEDURE", "X-Ray", null, new BigDecimal("30.000"), 1))
        ), fixture.adminAContext());

        service.recordPayment(scope, invoice.id(), new InvoiceModels.RecordPaymentRequest(
                new BigDecimal("15.000"), "JOD", "CASH", "P-1", "Half paid"
        ), fixture.adminAContext());

        // Attempting to refund 25.000 JOD when only 15.000 JOD was paid must be rejected
        assertThatThrownBy(() -> service.recordRefund(scope, invoice.id(), new InvoiceModels.RecordRefundRequest(
                new BigDecimal("25.000"), "JOD", "CASH", "Excessive refund", null
        ), fixture.adminAContext()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed paid amount");
    }

    @Test
    @DisplayName("cancels invoice and posts cancellation credit to ledger")
    void cancelsInvoiceAndAppendsCancellationCreditToLedger() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-BIL-CANCEL");

        InvoiceModels.InvoiceHeader invoice = service.createInvoice(scope, new InvoiceModels.CreateInvoiceRequest(
                patientId, null, null, "JOD", "Planned Surgery",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(null, null, "PROCEDURE", "Wisdom tooth removal", null, new BigDecimal("120.000"), 1))
        ), fixture.adminAContext());

        // Cancel the invoice
        service.cancelInvoice(scope, invoice.id(), new InvoiceModels.CancelInvoiceRequest(
                "Patient decided to postpone surgery"
        ), fixture.adminAContext());

        InvoiceModels.InvoiceHeader cancelled = service.getInvoiceDetail(scope, invoice.id()).invoice();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.balanceDue()).isEqualByComparingTo("0.000");

        // Check statement: net balance due must be 0.000 (120 charge - 120 cancellation credit)
        InvoiceModels.PatientStatement statement = service.getPatientStatement(scope, patientId, null, null);
        assertThat(statement.totalDebits()).isEqualByComparingTo("120.000");
        assertThat(statement.totalCredits()).isEqualByComparingTo("120.000");
        assertThat(statement.netBalanceDue()).isEqualByComparingTo("0.000");
    }
}
