package com.firstelement.crm.clinic.billing;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service orchestrating dental clinic billing, invoices, items, receipted payments,
 * refunds, and append-only patient ledger (BIL-01 to BIL-09, Slice S6).
 */
@Service
public class InvoiceService {

    private static final Set<String> VALID_PAYMENT_METHODS = Set.of("CASH", "CARD", "BANK_TRANSFER");
    private static final Set<String> VALID_REFUND_METHODS = Set.of("CASH", "CARD", "BANK_TRANSFER", "STORE_CREDIT");
    private static final Set<String> VALID_CURRENCIES = Set.of("JOD", "USD");

    private final InvoiceRepository repository;

    public InvoiceService(InvoiceRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists all clinic invoices with optional status filter.
     */
    public List<InvoiceModels.InvoiceHeader> listInvoices(ClinicScope scope, String status) {
        return repository.listInvoices(scope, status);
    }

    /**
     * Lists all invoices for a specific patient.
     */
    public List<InvoiceModels.InvoiceHeader> listInvoicesForPatient(ClinicScope scope, UUID patientId) {
        return repository.listInvoicesForPatient(scope, patientId);
    }

    /**
     * Retrieves full detail of an invoice with line items and payments.
     */
    public InvoiceModels.InvoiceDetail getInvoiceDetail(ClinicScope scope, UUID invoiceId) {
        InvoiceModels.InvoiceHeader header = repository.findInvoice(scope, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic invoice not found"));

        List<InvoiceModels.InvoiceItemRow> items = repository.listInvoiceItems(scope, invoiceId);
        List<InvoiceModels.PaymentRow> payments = repository.listPaymentsForInvoice(scope, invoiceId);

        return new InvoiceModels.InvoiceDetail(header, items, payments);
    }

    /**
     * Creates a new invoice with line items and records initial charge on patient ledger (BIL-01).
     */
    @Transactional
    public InvoiceModels.InvoiceHeader createInvoice(
            ClinicScope scope,
            InvoiceModels.CreateInvoiceRequest request,
            TenantContext caller) {
        if (request.patientId() == null) {
            throw new IllegalArgumentException("Patient ID is required to issue an invoice");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("Invoice must contain at least one line item");
        }

        String currency = request.currency() != null ? request.currency().toUpperCase() : "JOD";
        if (!VALID_CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency + ". Allowed: JOD, USD.");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (InvoiceModels.CreateInvoiceItemRequest item : request.items()) {
            if (item.unitPrice() == null || item.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Line item unit price cannot be negative");
            }
            int qty = item.quantity() != null && item.quantity() > 0 ? item.quantity() : 1;
            BigDecimal lineTotal = item.unitPrice().multiply(BigDecimal.valueOf(qty));
            subtotal = subtotal.add(lineTotal);
        }

        String invoiceNumber = repository.allocateSequenceNumber(scope, "INVOICE", "INV");
        UUID invoiceId = repository.insertInvoice(
                scope,
                request.patientId(),
                request.encounterId(),
                request.treatmentPlanId(),
                invoiceNumber,
                currency,
                subtotal,
                subtotal,
                request.notes()
        );

        for (InvoiceModels.CreateInvoiceItemRequest item : request.items()) {
            int qty = item.quantity() != null && item.quantity() > 0 ? item.quantity() : 1;
            BigDecimal lineTotal = item.unitPrice().multiply(BigDecimal.valueOf(qty));
            String itemType = item.itemType() != null ? item.itemType().toUpperCase() : "PROCEDURE";
            String desc = item.description() != null && !item.description().isBlank()
                    ? item.description().trim()
                    : "Dental Service";

            repository.insertInvoiceItem(
                    scope,
                    invoiceId,
                    item.treatmentPlanItemId(),
                    item.procedureId(),
                    itemType,
                    desc,
                    item.toothFdi(),
                    item.unitPrice(),
                    qty,
                    lineTotal
            );

            if (item.treatmentPlanItemId() != null) {
                repository.incrementPlanItemInvoiced(scope, item.treatmentPlanItemId(), lineTotal);
            }
        }

        // Post dual-entry charge on append-only patient ledger
        UUID callerUserId = caller != null ? caller.userId() : null;
        if (callerUserId != null) {
            repository.insertLedgerEntry(
                    scope,
                    request.patientId(),
                    "INVOICE_CHARGE",
                    "DEBIT",
                    subtotal,
                    currency,
                    invoiceId,
                    null,
                    null,
                    invoiceNumber,
                    "Invoice charge: " + invoiceNumber,
                    callerUserId
            );
        }

        return repository.findInvoice(scope, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice created but could not be retrieved"));
    }

    /**
     * Generates an invoice directly from approved items of a treatment plan.
     */
    @Transactional
    public InvoiceModels.InvoiceHeader generateInvoiceFromPlan(
            ClinicScope scope,
            InvoiceModels.GenerateInvoiceFromPlanRequest request,
            TenantContext caller) {
        if (request.patientId() == null) {
            throw new IllegalArgumentException("Patient ID is required");
        }
        if (request.treatmentPlanId() == null) {
            throw new IllegalArgumentException("Treatment plan ID is required");
        }

        List<InvoiceRepository.TreatmentPlanItemBillingDto> planItems =
                repository.listPlanItemsForBilling(scope, request.treatmentPlanId(), request.treatmentPlanItemIds());

        if (planItems.isEmpty()) {
            throw new IllegalArgumentException("No billable treatment plan items found for plan: " + request.treatmentPlanId());
        }

        List<InvoiceModels.CreateInvoiceItemRequest> items = new ArrayList<>();
        for (InvoiceRepository.TreatmentPlanItemBillingDto pi : planItems) {
            if (pi.remainingUninvoiced().compareTo(BigDecimal.ZERO) > 0) {
                items.add(new InvoiceModels.CreateInvoiceItemRequest(
                        pi.id(),
                        pi.procedureId(),
                        "PROCEDURE",
                        pi.procedureName() != null ? pi.procedureName() : "Dental Service",
                        pi.toothFdi(),
                        pi.remainingUninvoiced(),
                        1
                ));
            }
        }

        if (items.isEmpty()) {
            throw new IllegalStateException("All selected treatment plan items are already fully invoiced");
        }

        InvoiceModels.CreateInvoiceRequest invoiceRequest = new InvoiceModels.CreateInvoiceRequest(
                request.patientId(),
                null,
                request.treatmentPlanId(),
                request.currency() != null ? request.currency() : "JOD",
                request.notes(),
                items
        );

        return createInvoice(scope, invoiceRequest, caller);
    }

    /**
     * Applies a discount to an invoice (BIL-04) and posts credit entry to ledger.
     * Requires clinic manager / admin authorization.
     */
    @Transactional
    public InvoiceModels.InvoiceHeader applyDiscount(
            ClinicScope scope,
            UUID invoiceId,
            InvoiceModels.ApplyDiscountRequest request,
            TenantContext caller) {
        assertManagerOrAdmin(caller, "Only clinic managers and administrators can apply discounts");

        if (request.discountAmount() == null || request.discountAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Discount amount must be greater than zero");
        }
        if (request.discountReason() == null || request.discountReason().trim().isBlank()) {
            throw new IllegalArgumentException("Discount reason is mandatory (BIL-04)");
        }

        InvoiceModels.InvoiceHeader invoice = repository.findInvoice(scope, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic invoice not found"));

        if ("CANCELLED".equals(invoice.status())) {
            throw new IllegalStateException("Cannot apply a discount to a cancelled invoice");
        }
        if (request.discountAmount().compareTo(invoice.subtotal()) > 0) {
            throw new IllegalArgumentException("Discount cannot exceed the invoice subtotal (" + invoice.subtotal() + ")");
        }
        BigDecimal maxAllowedDiscount = invoice.subtotal().subtract(
                invoice.paidAmount() != null ? invoice.paidAmount() : BigDecimal.ZERO);
        if (request.discountAmount().compareTo(maxAllowedDiscount) > 0) {
            throw new IllegalArgumentException("Discount (" + request.discountAmount()
                    + ") cannot exceed the remaining unpaid balance (" + maxAllowedDiscount + ")");
        }

        BigDecimal previousDiscount = invoice.discountAmount() != null ? invoice.discountAmount() : BigDecimal.ZERO;
        BigDecimal discountDelta = request.discountAmount().subtract(previousDiscount);

        repository.applyDiscount(
                scope,
                invoiceId,
                request.discountAmount(),
                request.discountReason().trim(),
                caller.userId()
        );

        // Record net discount delta entry in patient ledger so invoice and ledger stay reconciled to the fils
        if (discountDelta.compareTo(BigDecimal.ZERO) > 0) {
            repository.insertLedgerEntry(
                    scope,
                    invoice.patientId(),
                    "DISCOUNT_CREDIT",
                    "CREDIT",
                    discountDelta,
                    invoice.currency(),
                    invoiceId,
                    null,
                    null,
                    invoice.invoiceNumber(),
                    "Manager discount applied: " + request.discountReason().trim(),
                    caller.userId()
            );
        } else if (discountDelta.compareTo(BigDecimal.ZERO) < 0) {
            repository.insertLedgerEntry(
                    scope,
                    invoice.patientId(),
                    "ADJUSTMENT",
                    "DEBIT",
                    discountDelta.abs(),
                    invoice.currency(),
                    invoiceId,
                    null,
                    null,
                    invoice.invoiceNumber(),
                    "Manager discount adjusted: " + request.discountReason().trim(),
                    caller.userId()
            );
        }

        return repository.findInvoice(scope, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found after applying discount"));
    }

    /**
     * Cancels an invoice with a mandatory reason (BIL-05) and posts cancellation credit to ledger.
     */
    @Transactional
    public InvoiceModels.InvoiceHeader cancelInvoice(
            ClinicScope scope,
            UUID invoiceId,
            InvoiceModels.CancelInvoiceRequest request,
            TenantContext caller) {
        assertManagerOrAdmin(caller, "Only clinic managers and administrators can cancel invoices");

        if (request.cancellationReason() == null || request.cancellationReason().trim().isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is mandatory (BIL-05)");
        }

        InvoiceModels.InvoiceHeader invoice = repository.findInvoice(scope, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic invoice not found"));

        if ("CANCELLED".equals(invoice.status())) {
            throw new IllegalStateException("Invoice is already cancelled");
        }
        if (invoice.paidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Cannot cancel an invoice with recorded payments. Please issue a refund first.");
        }

        repository.cancelInvoice(scope, invoiceId, caller.userId(), request.cancellationReason().trim());

        // Offset the original invoice charge in ledger
        repository.insertLedgerEntry(
                scope,
                invoice.patientId(),
                "CANCELLATION_CREDIT",
                "CREDIT",
                invoice.totalAmount(),
                invoice.currency(),
                invoiceId,
                null,
                null,
                invoice.invoiceNumber(),
                "Invoice cancelled: " + request.cancellationReason().trim(),
                caller.userId()
        );

        return repository.findInvoice(scope, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found after cancellation"));
    }

    /**
     * Records a payment against an invoice with idempotency protection and recalculates balances (BIL-02, BIL-03).
     */
    @Transactional
    public InvoiceModels.PaymentReceipt recordPayment(
            ClinicScope scope,
            UUID invoiceId,
            InvoiceModels.RecordPaymentRequest request,
            TenantContext caller) {
        // Idempotency check: if already processed, return existing receipt without duplicate charge
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<InvoiceModels.PaymentReceipt> existing =
                    repository.findPaymentByIdempotencyKey(scope, request.idempotencyKey().trim());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
        if (request.paymentMethod() == null || !VALID_PAYMENT_METHODS.contains(request.paymentMethod().toUpperCase())) {
            throw new IllegalArgumentException("Invalid payment method. Allowed: CASH, CARD, BANK_TRANSFER.");
        }

        InvoiceModels.InvoiceHeader invoice = repository.findInvoice(scope, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic invoice not found"));

        if ("CANCELLED".equals(invoice.status())) {
            throw new IllegalStateException("Cannot record payment against a cancelled invoice");
        }
        if (invoice.balanceDue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Invoice is already fully paid");
        }
        if (request.amount().compareTo(invoice.balanceDue()) > 0) {
            throw new IllegalArgumentException("Payment amount (" + request.amount()
                    + ") exceeds the balance due (" + invoice.balanceDue() + ")");
        }

        String currency = request.currency() != null ? request.currency().toUpperCase() : invoice.currency();
        if (!currency.equalsIgnoreCase(invoice.currency())) {
            throw new IllegalArgumentException("Payment currency (" + currency
                    + ") must match invoice currency (" + invoice.currency() + ") (BIL-07).");
        }

        String paymentNumber = repository.allocateSequenceNumber(scope, "PAYMENT", "REC");
        String idempotencyKey = (request.idempotencyKey() != null && !request.idempotencyKey().isBlank())
                ? request.idempotencyKey().trim()
                : null;

        UUID paymentId = repository.insertPayment(
                scope,
                invoiceId,
                invoice.patientId(),
                paymentNumber,
                request.amount(),
                currency,
                request.paymentMethod().toUpperCase(),
                request.referenceNumber(),
                request.notes(),
                caller.userId(),
                idempotencyKey
        );

        repository.recalculateInvoicePaymentTotals(scope, invoiceId);

        // Record credit on patient ledger
        repository.insertLedgerEntry(
                scope,
                invoice.patientId(),
                "PAYMENT_CREDIT",
                "CREDIT",
                request.amount(),
                currency,
                invoiceId,
                paymentId,
                null,
                paymentNumber,
                "Payment received against invoice: " + invoice.invoiceNumber(),
                caller.userId()
        );

        return repository.findPaymentReceipt(scope, paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment receipt not found after recording"));
    }

    /**
     * Issues a refund or payment reversal against an invoice with mandatory reason and manager authorization.
     */
    @Transactional
    public InvoiceModels.RefundRow recordRefund(
            ClinicScope scope,
            UUID invoiceId,
            InvoiceModels.RecordRefundRequest request,
            TenantContext caller) {
        assertManagerOrAdmin(caller, "Only clinic managers and administrators can issue refunds");

        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }
        if (request.reason() == null || request.reason().trim().isBlank()) {
            throw new IllegalArgumentException("Refund reason is mandatory");
        }
        String method = request.refundMethod() != null ? request.refundMethod().toUpperCase() : "CASH";
        if (!VALID_REFUND_METHODS.contains(method)) {
            throw new IllegalArgumentException("Invalid refund method: " + method + ". Allowed: CASH, CARD, BANK_TRANSFER, STORE_CREDIT.");
        }

        InvoiceModels.InvoiceHeader invoice = repository.findInvoice(scope, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic invoice not found"));

        if ("CANCELLED".equals(invoice.status())) {
            throw new IllegalStateException("Cannot issue a refund for a cancelled invoice");
        }
        if (request.amount().compareTo(invoice.paidAmount()) > 0) {
            throw new IllegalArgumentException("Refund amount (" + request.amount()
                    + ") cannot exceed paid amount on invoice (" + invoice.paidAmount() + ")");
        }

        String currency = request.currency() != null && !request.currency().isBlank()
                ? request.currency().trim().toUpperCase()
                : invoice.currency();
        if (!currency.equalsIgnoreCase(invoice.currency())) {
            throw new IllegalArgumentException("Refund currency (" + currency
                    + ") must match invoice currency (" + invoice.currency() + ") (BIL-07).");
        }
        String refundNumber = repository.allocateSequenceNumber(scope, "REFUND", "REF");

        UUID refundId = repository.insertRefund(
                scope,
                invoiceId,
                request.paymentId(),
                invoice.patientId(),
                refundNumber,
                request.amount(),
                currency,
                method,
                request.reason().trim(),
                caller.userId()
        );

        repository.recalculateInvoicePaymentTotals(scope, invoiceId);

        // Record debit entry on patient ledger for the refund
        repository.insertLedgerEntry(
                scope,
                invoice.patientId(),
                "REFUND_DEBIT",
                "DEBIT",
                request.amount(),
                currency,
                invoiceId,
                request.paymentId(),
                refundId,
                refundNumber,
                "Refund issued: " + request.reason().trim(),
                caller.userId()
        );

        return repository.findRefund(scope, refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund recorded but could not be retrieved"));
    }

    /**
     * Lists all refunds for a specific invoice.
     */
    public List<InvoiceModels.RefundRow> listRefundsForInvoice(ClinicScope scope, UUID invoiceId) {
        return repository.listRefundsForInvoice(scope, invoiceId);
    }

    /**
     * Retrieves the chronological ledger entries for a patient.
     */
    public List<InvoiceModels.PatientLedgerEntryRow> getPatientLedger(
            ClinicScope scope,
            UUID patientId,
            LocalDateTime fromDate,
            LocalDateTime toDate) {
        return repository.listPatientLedger(scope, patientId, fromDate, toDate);
    }

    /**
     * Produces a consolidated statement of account for a patient.
     */
    public InvoiceModels.PatientStatement getPatientStatement(
            ClinicScope scope,
            UUID patientId,
            LocalDateTime fromDate,
            LocalDateTime toDate) {
        return repository.getPatientStatement(scope, patientId, fromDate, toDate);
    }

    /**
     * Retrieves a payment receipt for printing/PDF (BIL-06).
     */
    public InvoiceModels.PaymentReceipt getPaymentReceipt(ClinicScope scope, UUID paymentId) {
        return repository.findPaymentReceipt(scope, paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment receipt not found"));
    }

    /**
     * Lists all payments across the clinic.
     */
    public List<InvoiceModels.PaymentRow> listAllPayments(ClinicScope scope) {
        return repository.listPayments(scope);
    }

    private static void assertManagerOrAdmin(TenantContext caller, String message) {
        if (caller == null) {
            throw new AccessDeniedException(message);
        }
        if (caller.role() != UserRole.ADMIN) {
            throw new AccessDeniedException(message);
        }
    }
}
