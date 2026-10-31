package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.billing.InvoiceModels;
import com.firstelement.crm.clinic.billing.InvoiceService;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for Dental Clinic Invoices, Payments, and Receipts (BIL-01 to BIL-09).
 */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicBillingController {

    private final InvoiceService invoiceService;
    private final TenantQueryGuard guard;

    public ClinicBillingController(InvoiceService invoiceService, TenantQueryGuard guard) {
        this.invoiceService = invoiceService;
        this.guard = guard;
    }

    /**
     * Lists all clinic invoices (BIL-01).
     */
    @GetMapping("/invoices")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List clinic invoices", description = "Returns invoices with optional status filter.")
    public List<InvoiceModels.InvoiceHeader> listInvoices(
            @Parameter(description = "Optional status filter (ISSUED, PARTIALLY_PAID, PAID, CANCELLED)")
            @RequestParam(required = false) String status,
            @Parameter(description = "Optional project scoping")
            @RequestParam(required = false) String projectId) {
        return invoiceService.listInvoices(scope(projectId), status);
    }

    /**
     * Retrieves full invoice details with line items and payments.
     */
    @GetMapping("/invoices/{invoiceId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get invoice detail by ID")
    public InvoiceModels.InvoiceDetail getInvoiceDetail(
            @PathVariable("invoiceId") String invoiceId,
            @RequestParam(required = false) String projectId) {
        UUID id = RequestParams.requiredUuid(invoiceId, "invoiceId");
        return invoiceService.getInvoiceDetail(scope(projectId), id);
    }

    /**
     * Lists all invoices for a specific patient.
     */
    @GetMapping("/patients/{patientId}/invoices")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List all invoices for a patient")
    public List<InvoiceModels.InvoiceHeader> listPatientInvoices(
            @PathVariable("patientId") String patientId,
            @RequestParam(required = false) String projectId) {
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        return invoiceService.listInvoicesForPatient(scope(projectId), patId);
    }

    /**
     * Issues a new clinic invoice (BIL-01).
     */
    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Issue a new clinic invoice", description = "Creates invoice, line items, and updates plan items.")
    public InvoiceModels.InvoiceHeader createInvoice(
            @RequestParam(required = false) String projectId,
            @RequestBody InvoiceModels.CreateInvoiceRequest request) {
        TenantContext caller = TenantContextHolder.get();
        return invoiceService.createInvoice(writeScope(projectId), request, caller);
    }

    /**
     * Applies a discount to an invoice (BIL-04).
     */
    @PostMapping("/invoices/{invoiceId}/discount")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Apply discount to an invoice", description = "Clinic manager or admin only (BIL-04).")
    public InvoiceModels.InvoiceHeader applyDiscount(
            @PathVariable("invoiceId") String invoiceId,
            @RequestParam(required = false) String projectId,
            @RequestBody InvoiceModels.ApplyDiscountRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID id = RequestParams.requiredUuid(invoiceId, "invoiceId");
        return invoiceService.applyDiscount(writeScope(projectId), id, request, caller);
    }

    /**
     * Cancels an invoice with a mandatory reason (BIL-05).
     */
    @PostMapping("/invoices/{invoiceId}/cancel")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Cancel an invoice", description = "Clinic manager or admin only. Mandatory reason (BIL-05).")
    public InvoiceModels.InvoiceHeader cancelInvoice(
            @PathVariable("invoiceId") String invoiceId,
            @RequestParam(required = false) String projectId,
            @RequestBody InvoiceModels.CancelInvoiceRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID id = RequestParams.requiredUuid(invoiceId, "invoiceId");
        return invoiceService.cancelInvoice(writeScope(projectId), id, request, caller);
    }

    /**
     * Lists all recorded payments across the clinic (BIL-02).
     */
    @GetMapping("/payments")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List clinic payments")
    public List<InvoiceModels.PaymentRow> listPayments(
            @RequestParam(required = false) String projectId) {
        return invoiceService.listAllPayments(scope(projectId));
    }

    /**
     * Records a payment against an invoice (BIL-02).
     */
    @PostMapping("/invoices/{invoiceId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Record a payment against an invoice", description = "Generates receipt and recalculates balances.")
    public InvoiceModels.PaymentReceipt recordPayment(
            @PathVariable("invoiceId") String invoiceId,
            @RequestParam(required = false) String projectId,
            @RequestBody InvoiceModels.RecordPaymentRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID id = RequestParams.requiredUuid(invoiceId, "invoiceId");
        return invoiceService.recordPayment(writeScope(projectId), id, request, caller);
    }

    /**
     * Gets a payment receipt for printing/PDF export (BIL-06).
     */
    @GetMapping("/payments/{paymentId}/receipt")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get payment receipt detail", description = "Contains clinic, patient, payment method, and amount (BIL-06).")
    public InvoiceModels.PaymentReceipt getReceipt(
            @PathVariable("paymentId") String paymentId,
            @RequestParam(required = false) String projectId) {
        UUID id = RequestParams.requiredUuid(paymentId, "paymentId");
        return invoiceService.getPaymentReceipt(scope(projectId), id);
    }

    /**
     * Generates an invoice directly from approved items of a treatment plan.
     */
    @PostMapping("/invoices/from-plan")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Generate invoice from treatment plan", description = "Creates an invoice from approved treatment plan items.")
    public InvoiceModels.InvoiceHeader generateInvoiceFromPlan(
            @RequestParam(required = false) String projectId,
            @RequestBody InvoiceModels.GenerateInvoiceFromPlanRequest request) {
        TenantContext caller = TenantContextHolder.get();
        return invoiceService.generateInvoiceFromPlan(writeScope(projectId), request, caller);
    }

    /**
     * Records a refund or payment reversal against an invoice.
     */
    @PostMapping("/invoices/{invoiceId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Record a refund or payment reversal", description = "Requires clinic manager or admin authorization.")
    public InvoiceModels.RefundRow recordRefund(
            @PathVariable("invoiceId") String invoiceId,
            @RequestParam(required = false) String projectId,
            @RequestBody InvoiceModels.RecordRefundRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID id = RequestParams.requiredUuid(invoiceId, "invoiceId");
        return invoiceService.recordRefund(writeScope(projectId), id, request, caller);
    }

    /**
     * Lists all refunds recorded for an invoice.
     */
    @GetMapping("/invoices/{invoiceId}/refunds")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List refunds for an invoice")
    public List<InvoiceModels.RefundRow> listInvoiceRefunds(
            @PathVariable("invoiceId") String invoiceId,
            @RequestParam(required = false) String projectId) {
        UUID id = RequestParams.requiredUuid(invoiceId, "invoiceId");
        return invoiceService.listRefundsForInvoice(scope(projectId), id);
    }

    /**
     * Retrieves the append-only ledger entries for a patient.
     */
    @GetMapping("/patients/{patientId}/ledger")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get patient ledger entries", description = "Returns chronological dual-entry ledger transactions.")
    public List<InvoiceModels.PatientLedgerEntryRow> getPatientLedger(
            @PathVariable("patientId") String patientId,
            @RequestParam(required = false) String projectId) {
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        return invoiceService.getPatientLedger(scope(projectId), patId, null, null);
    }

    /**
     * Retrieves a consolidated statement of account for a patient.
     */
    @GetMapping("/patients/{patientId}/statement")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get patient statement of account", description = "Consolidated statement with opening/closing balances and transactions.")
    public InvoiceModels.PatientStatement getPatientStatement(
            @PathVariable("patientId") String patientId,
            @RequestParam(required = false) String projectId) {
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        return invoiceService.getPatientStatement(scope(projectId), patId, null, null);
    }

    private ClinicScope scope(String projectId) {
        return ClinicScope.forRequestedProject(
                guard, TenantContextHolder.get(), RequestParams.optionalUuid(projectId, "projectId"));
    }

    private ClinicScope writeScope(String projectId) {
        ClinicScope scope = scope(projectId);
        if (scope.isProjectScoped()) {
            return scope;
        }
        var reachable = scope.accessibleProjectIds();
        if (reachable.size() == 1) {
            return ClinicScope.forRequestedProject(guard, TenantContextHolder.get(), reachable.get(0));
        }
        throw new ResourceNotFoundException("Resource not found");
    }
}
