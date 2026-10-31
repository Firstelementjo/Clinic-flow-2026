package com.firstelement.crm.clinic.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Domain and API models for dental clinic billing, invoices, and payments (BIL-01 to BIL-09).
 */
public final class InvoiceModels {

    private InvoiceModels() {
    }

    /**
     * An invoice header row with totals, balances, and cancellation status.
     */
    public record InvoiceHeader(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String patientName,
            String patientNo,
            UUID encounterId,
            UUID treatmentPlanId,
            String treatmentPlanTitle,
            String invoiceNumber,
            String currency,
            BigDecimal subtotal,
            BigDecimal discountAmount,
            String discountReason,
            UUID discountAppliedBy,
            String discountAppliedByName,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal balanceDue,
            String status,
            String notes,
            LocalDateTime issuedAt,
            LocalDateTime cancelledAt,
            UUID cancelledBy,
            String cancelledByName,
            String cancellationReason,
            int itemCount,
            int paymentCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * One line item on an invoice.
     */
    public record InvoiceItemRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID invoiceId,
            UUID treatmentPlanItemId,
            UUID procedureId,
            String procedureName,
            String itemType,
            String description,
            Integer toothFdi,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal totalPrice,
            LocalDateTime createdAt) {
    }

    /**
     * A recorded payment against an invoice.
     */
    public record PaymentRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID invoiceId,
            String invoiceNumber,
            UUID patientId,
            String patientName,
            String paymentNumber,
            BigDecimal amount,
            String currency,
            String paymentMethod,
            String referenceNumber,
            String notes,
            UUID recordedByUserId,
            String recordedByUserName,
            LocalDateTime paymentDate,
            LocalDateTime createdAt) {
    }

    /**
     * Complete invoice details with line items and payments.
     */
    public record InvoiceDetail(
            InvoiceHeader invoice,
            List<InvoiceItemRow> items,
            List<PaymentRow> payments) {
    }

    /**
     * Line item creation payload.
     */
    public record CreateInvoiceItemRequest(
            UUID treatmentPlanItemId,
            UUID procedureId,
            String itemType,
            String description,
            Integer toothFdi,
            BigDecimal unitPrice,
            Integer quantity) {
    }

    /**
     * Invoice creation payload (BIL-01).
     */
    public record CreateInvoiceRequest(
            UUID patientId,
            UUID encounterId,
            UUID treatmentPlanId,
            String currency,
            String notes,
            List<CreateInvoiceItemRequest> items) {
    }

    /**
     * Discount application payload (BIL-04).
     */
    public record ApplyDiscountRequest(
            BigDecimal discountAmount,
            String discountReason) {
    }

    /**
     * Invoice cancellation payload with mandatory reason (BIL-05).
     */
    public record CancelInvoiceRequest(
            String cancellationReason) {
    }

    /**
     * Payment recording payload (BIL-02) with optional idempotency key.
     */
    public record RecordPaymentRequest(
            BigDecimal amount,
            String currency,
            String paymentMethod,
            String referenceNumber,
            String notes,
            String idempotencyKey) {

        public RecordPaymentRequest(BigDecimal amount, String currency, String paymentMethod, String referenceNumber, String notes) {
            this(amount, currency, paymentMethod, referenceNumber, notes, null);
        }
    }

    /**
     * Printable/PDF-exportable payment receipt (BIL-06).
     */
    public record PaymentReceipt(
            UUID paymentId,
            String paymentNumber,
            UUID invoiceId,
            String invoiceNumber,
            String clinicName,
            UUID patientId,
            String patientName,
            String patientNo,
            BigDecimal amount,
            String currency,
            String paymentMethod,
            String referenceNumber,
            String notes,
            LocalDateTime paymentDate,
            String recordedByUserName,
            BigDecimal remainingInvoiceBalance) {
    }

    /**
     * Refund or payment reversal payload.
     */
    public record RecordRefundRequest(
            BigDecimal amount,
            String currency,
            String refundMethod,
            String reason,
            UUID paymentId) {
    }

    /**
     * Detail of an issued refund or payment reversal.
     */
    public record RefundRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID invoiceId,
            String invoiceNumber,
            UUID paymentId,
            String paymentNumber,
            UUID patientId,
            String patientName,
            String refundNumber,
            BigDecimal amount,
            String currency,
            String refundMethod,
            String reason,
            UUID approvedByUserId,
            String approvedByUserName,
            LocalDateTime refundDate,
            LocalDateTime createdAt) {
    }

    /**
     * An immutable entry in the dual-entry append-only patient ledger.
     */
    public record PatientLedgerEntryRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String entryType,
            String direction,
            BigDecimal amount,
            String currency,
            UUID invoiceId,
            String invoiceNumber,
            UUID paymentId,
            String paymentNumber,
            UUID refundId,
            String refundNumber,
            String referenceNumber,
            String description,
            UUID recordedByUserId,
            String recordedByUserName,
            LocalDateTime entryDate,
            LocalDateTime createdAt) {
    }

    /**
     * Request payload to generate an invoice directly from an approved treatment plan.
     */
    public record GenerateInvoiceFromPlanRequest(
            UUID patientId,
            UUID treatmentPlanId,
            List<UUID> treatmentPlanItemIds,
            String currency,
            String notes) {
    }

    /**
     * Consolidated patient statement of account.
     */
    public record PatientStatement(
            UUID patientId,
            String patientName,
            String patientNo,
            String currency,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            BigDecimal netBalanceDue,
            List<PatientLedgerEntryRow> entries) {
    }
}
