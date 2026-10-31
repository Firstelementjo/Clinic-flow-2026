package com.firstelement.crm.clinic.billing;

import com.firstelement.crm.clinic.ClinicScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Scoped repository for dental clinic billing, invoices, items, and receipted payments.
 * All operations are strictly guarded by {@link ClinicScope}.
 */
@Repository
public class InvoiceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public InvoiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Allocates the next sequential invoice or payment number per organization (BIL-09).
     */
    public String allocateSequenceNumber(ClinicScope scope, String sequenceType, String prefix) {
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("sequenceType", sequenceType);

            Long nextVal = jdbc.queryForObject("""
                    insert into clinic_invoice_sequences ("organizationId", "sequenceType", "currentValue")
                    values (:orgId, :sequenceType, 1)
                    on conflict ("organizationId", "sequenceType")
                    do update set "currentValue" = clinic_invoice_sequences."currentValue" + 1
                    returning "currentValue"
                    """, params, Long.class);

            long val = nextVal != null ? nextVal : 1L;
            int year = LocalDate.now().getYear();
            return String.format("%s-%d-%04d", prefix, year, val);
        });
    }

    /**
     * Inserts a new invoice header row.
     */
    public UUID insertInvoice(
            ClinicScope scope,
            UUID patientId,
            UUID encounterId,
            UUID treatmentPlanId,
            String invoiceNumber,
            String currency,
            BigDecimal subtotal,
            BigDecimal totalAmount,
            String notes) {
        UUID invoiceId = UUID.randomUUID();
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", invoiceId)
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("patientId", patientId)
                    .addValue("encounterId", encounterId)
                    .addValue("treatmentPlanId", treatmentPlanId)
                    .addValue("invoiceNumber", invoiceNumber)
                    .addValue("currency", currency)
                    .addValue("subtotal", subtotal)
                    .addValue("totalAmount", totalAmount)
                    .addValue("balanceDue", totalAmount)
                    .addValue("notes", notes);

            jdbc.update("""
                    insert into clinic_invoices (
                        id, "organizationId", "projectId", "patientId", "encounterId", "treatmentPlanId",
                        "invoiceNumber", "currency", "subtotal", "discountAmount", "totalAmount",
                        "paidAmount", "balanceDue", "status", "notes"
                    ) values (
                        :id, :orgId, :projId, :patientId, :encounterId, :treatmentPlanId,
                        :invoiceNumber, :currency, :subtotal, 0.000, :totalAmount,
                        0.000, :balanceDue, 'ISSUED', :notes
                    )
                    """, params);
            return invoiceId;
        });
    }

    /**
     * Inserts an invoice line item.
     */
    public UUID insertInvoiceItem(
            ClinicScope scope,
            UUID invoiceId,
            UUID treatmentPlanItemId,
            UUID procedureId,
            String itemType,
            String description,
            Integer toothFdi,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal totalPrice) {
        UUID itemId = UUID.randomUUID();
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", itemId)
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("invoiceId", invoiceId)
                    .addValue("treatmentPlanItemId", treatmentPlanItemId)
                    .addValue("procedureId", procedureId)
                    .addValue("itemType", itemType)
                    .addValue("description", description)
                    .addValue("toothFdi", toothFdi)
                    .addValue("unitPrice", unitPrice)
                    .addValue("quantity", quantity)
                    .addValue("totalPrice", totalPrice);

            jdbc.update("""
                    insert into clinic_invoice_items (
                        id, "organizationId", "projectId", "invoiceId", "treatmentPlanItemId", "procedureId",
                        "itemType", "description", "toothFdi", "unitPrice", "quantity", "totalPrice"
                    ) values (
                        :id, :orgId, :projId, :invoiceId, :treatmentPlanItemId, :procedureId,
                        :itemType, :description, :toothFdi, :unitPrice, :quantity, :totalPrice
                    )
                    """, params);
            return itemId;
        });
    }

    /**
     * Finds an invoice by ID with patient and plan joins.
     */
    public Optional<InvoiceModels.InvoiceHeader> findInvoice(ClinicScope scope, UUID invoiceId) {
        return scope.read(() -> {
            try {
                return Optional.ofNullable(jdbc.queryForObject("""
                        select ci."id", ci."organizationId", ci."projectId", ci."patientId",
                               p."displayName" as "patientName", p."patientNo",
                               ci."encounterId", ci."treatmentPlanId", tp."title" as "treatmentPlanTitle",
                               ci."invoiceNumber", ci."currency", ci."subtotal",
                               ci."discountAmount", ci."discountReason", ci."discountAppliedBy",
                               ud."fullName" as "discountAppliedByName",
                               ci."totalAmount", ci."paidAmount", ci."balanceDue",
                               ci."status", ci."notes", ci."issuedAt",
                               ci."cancelledAt", ci."cancelledBy", uc."fullName" as "cancelledByName",
                               ci."cancellationReason",
                               coalesce(items.item_count, 0) as "itemCount",
                               coalesce(payments.pmt_count, 0) as "paymentCount",
                               ci."createdAt", ci."updatedAt"
                          from "clinic_invoices" ci
                          inner join "patients" p on p."id" = ci."patientId"
                          left join "treatment_plans" tp on tp."id" = ci."treatmentPlanId"
                          left join "users" ud on ud."id" = ci."discountAppliedBy"
                          left join "users" uc on uc."id" = ci."cancelledBy"
                          left join (
                              select "invoiceId", count("id") as item_count
                                from "clinic_invoice_items"
                               group by "invoiceId"
                          ) items on items."invoiceId" = ci."id"
                          left join (
                              select "invoiceId", count("id") as pmt_count
                                from "clinic_payments"
                               group by "invoiceId"
                          ) payments on payments."invoiceId" = ci."id"
                         where %s
                           and ci."id" = :invoiceId
                        """.formatted(scope.predicate("ci")),
                        scope.bind().addValue("invoiceId", invoiceId),
                        InvoiceRepository::mapInvoiceHeader));
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Lists all invoices for a patient, newest first.
     */
    public List<InvoiceModels.InvoiceHeader> listInvoicesForPatient(ClinicScope scope, UUID patientId) {
        return scope.read(() -> jdbc.query("""
                select ci."id", ci."organizationId", ci."projectId", ci."patientId",
                       p."displayName" as "patientName", p."patientNo",
                       ci."encounterId", ci."treatmentPlanId", tp."title" as "treatmentPlanTitle",
                       ci."invoiceNumber", ci."currency", ci."subtotal",
                       ci."discountAmount", ci."discountReason", ci."discountAppliedBy",
                       ud."fullName" as "discountAppliedByName",
                       ci."totalAmount", ci."paidAmount", ci."balanceDue",
                       ci."status", ci."notes", ci."issuedAt",
                       ci."cancelledAt", ci."cancelledBy", uc."fullName" as "cancelledByName",
                       ci."cancellationReason",
                       coalesce(items.item_count, 0) as "itemCount",
                       coalesce(payments.pmt_count, 0) as "paymentCount",
                       ci."createdAt", ci."updatedAt"
                  from "clinic_invoices" ci
                  inner join "patients" p on p."id" = ci."patientId"
                  left join "treatment_plans" tp on tp."id" = ci."treatmentPlanId"
                  left join "users" ud on ud."id" = ci."discountAppliedBy"
                  left join "users" uc on uc."id" = ci."cancelledBy"
                  left join (
                      select "invoiceId", count("id") as item_count
                        from "clinic_invoice_items"
                       group by "invoiceId"
                  ) items on items."invoiceId" = ci."id"
                  left join (
                      select "invoiceId", count("id") as pmt_count
                        from "clinic_payments"
                       group by "invoiceId"
                  ) payments on payments."invoiceId" = ci."id"
                 where %s
                   and ci."patientId" = :patientId
                 order by ci."issuedAt" desc
                """.formatted(scope.predicate("ci")),
                scope.bind().addValue("patientId", patientId),
                InvoiceRepository::mapInvoiceHeader));
    }

    /**
     * Lists all clinic invoices with optional status filtering.
     */
    public List<InvoiceModels.InvoiceHeader> listInvoices(ClinicScope scope, String status) {
        return scope.read(() -> {
            String filter = (status != null && !status.isBlank())
                    ? " and ci.\"status\" = :status "
                    : "";
            MapSqlParameterSource params = scope.bind();
            if (!filter.isEmpty()) {
                params.addValue("status", status.toUpperCase());
            }

            return jdbc.query("""
                    select ci."id", ci."organizationId", ci."projectId", ci."patientId",
                           p."displayName" as "patientName", p."patientNo",
                           ci."encounterId", ci."treatmentPlanId", tp."title" as "treatmentPlanTitle",
                           ci."invoiceNumber", ci."currency", ci."subtotal",
                           ci."discountAmount", ci."discountReason", ci."discountAppliedBy",
                           ud."fullName" as "discountAppliedByName",
                           ci."totalAmount", ci."paidAmount", ci."balanceDue",
                           ci."status", ci."notes", ci."issuedAt",
                           ci."cancelledAt", ci."cancelledBy", uc."fullName" as "cancelledByName",
                           ci."cancellationReason",
                           coalesce(items.item_count, 0) as "itemCount",
                           coalesce(payments.pmt_count, 0) as "paymentCount",
                           ci."createdAt", ci."updatedAt"
                      from "clinic_invoices" ci
                      inner join "patients" p on p."id" = ci."patientId"
                      left join "treatment_plans" tp on tp."id" = ci."treatmentPlanId"
                      left join "users" ud on ud."id" = ci."discountAppliedBy"
                      left join "users" uc on uc."id" = ci."cancelledBy"
                      left join (
                          select "invoiceId", count("id") as item_count
                            from "clinic_invoice_items"
                           group by "invoiceId"
                      ) items on items."invoiceId" = ci."id"
                      left join (
                          select "invoiceId", count("id") as pmt_count
                            from "clinic_payments"
                           group by "invoiceId"
                      ) payments on payments."invoiceId" = ci."id"
                     where %s
                       %s
                     order by ci."issuedAt" desc
                    """.formatted(scope.predicate("ci"), filter),
                    params,
                    InvoiceRepository::mapInvoiceHeader);
        });
    }

    /**
     * Lists all line items for an invoice.
     */
    public List<InvoiceModels.InvoiceItemRow> listInvoiceItems(ClinicScope scope, UUID invoiceId) {
        return scope.read(() -> jdbc.query("""
                select cii."id", cii."organizationId", cii."projectId", cii."invoiceId",
                       cii."treatmentPlanItemId", cii."procedureId", pr."name" as "procedureName",
                       cii."itemType", cii."description", cii."toothFdi",
                       cii."unitPrice", cii."quantity", cii."totalPrice", cii."createdAt"
                  from "clinic_invoice_items" cii
                  left join "procedures" pr on pr."id" = cii."procedureId"
                 where %s
                   and cii."invoiceId" = :invoiceId
                 order by cii."createdAt" asc
                """.formatted(scope.predicate("cii")),
                scope.bind().addValue("invoiceId", invoiceId),
                InvoiceRepository::mapInvoiceItemRow));
    }

    /**
     * Inserts a payment against an invoice (BIL-02).
     */
    public UUID insertPayment(
            ClinicScope scope,
            UUID invoiceId,
            UUID patientId,
            String paymentNumber,
            BigDecimal amount,
            String currency,
            String paymentMethod,
            String referenceNumber,
            String notes,
            UUID recordedByUserId) {
        return insertPayment(scope, invoiceId, patientId, paymentNumber, amount, currency, paymentMethod, referenceNumber, notes, recordedByUserId, null);
    }

    /**
     * Inserts a payment against an invoice with an optional idempotency key.
     */
    public UUID insertPayment(
            ClinicScope scope,
            UUID invoiceId,
            UUID patientId,
            String paymentNumber,
            BigDecimal amount,
            String currency,
            String paymentMethod,
            String referenceNumber,
            String notes,
            UUID recordedByUserId,
            String idempotencyKey) {
        UUID paymentId = UUID.randomUUID();
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", paymentId)
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("invoiceId", invoiceId)
                    .addValue("patientId", patientId)
                    .addValue("paymentNumber", paymentNumber)
                    .addValue("amount", amount)
                    .addValue("currency", currency)
                    .addValue("paymentMethod", paymentMethod)
                    .addValue("referenceNumber", referenceNumber)
                    .addValue("notes", notes)
                    .addValue("recordedByUserId", recordedByUserId)
                    .addValue("idempotencyKey", idempotencyKey);

            jdbc.update("""
                    insert into clinic_payments (
                        id, "organizationId", "projectId", "invoiceId", "patientId",
                        "paymentNumber", "amount", "currency", "paymentMethod",
                        "referenceNumber", "notes", "recordedByUserId", "idempotencyKey"
                    ) values (
                        :id, :orgId, :projId, :invoiceId, :patientId,
                        :paymentNumber, :amount, :currency, :paymentMethod,
                        :referenceNumber, :notes, :recordedByUserId, :idempotencyKey
                    )
                    """, params);
            return paymentId;
        });
    }

    /**
     * Finds an existing payment receipt by idempotency key within the organization scope.
     */
    public Optional<InvoiceModels.PaymentReceipt> findPaymentByIdempotencyKey(ClinicScope scope, String idempotencyKey) {
        return scope.read(() -> {
            try {
                return Optional.ofNullable(jdbc.queryForObject("""
                        select cp."id" as "paymentId", cp."paymentNumber", cp."invoiceId",
                               ci."invoiceNumber", o."name" as "clinicName",
                               cp."patientId", p."displayName" as "patientName", p."patientNo",
                               cp."amount", cp."currency", cp."paymentMethod",
                               cp."referenceNumber", cp."notes", cp."paymentDate",
                               u."fullName" as "recordedByUserName",
                               ci."balanceDue" as "remainingInvoiceBalance"
                          from "clinic_payments" cp
                          inner join "clinic_invoices" ci on ci."id" = cp."invoiceId"
                          inner join "organizations" o on o."id" = cp."organizationId"
                          inner join "patients" p on p."id" = cp."patientId"
                          inner join "users" u on u."id" = cp."recordedByUserId"
                         where %s
                           and cp."idempotencyKey" = :idempotencyKey
                        """.formatted(scope.predicate("cp")),
                        scope.bind().addValue("idempotencyKey", idempotencyKey),
                        InvoiceRepository::mapPaymentReceipt));
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Lists all payments for an invoice.
     */
    public List<InvoiceModels.PaymentRow> listPaymentsForInvoice(ClinicScope scope, UUID invoiceId) {
        return scope.read(() -> jdbc.query("""
                select cp."id", cp."organizationId", cp."projectId", cp."invoiceId",
                       ci."invoiceNumber", cp."patientId", p."displayName" as "patientName",
                       cp."paymentNumber", cp."amount", cp."currency", cp."paymentMethod",
                       cp."referenceNumber", cp."notes", cp."recordedByUserId",
                       u."fullName" as "recordedByUserName", cp."paymentDate", cp."createdAt"
                  from "clinic_payments" cp
                  inner join "clinic_invoices" ci on ci."id" = cp."invoiceId"
                  inner join "patients" p on p."id" = cp."patientId"
                  inner join "users" u on u."id" = cp."recordedByUserId"
                 where %s
                   and cp."invoiceId" = :invoiceId
                 order by cp."paymentDate" desc
                """.formatted(scope.predicate("cp")),
                scope.bind().addValue("invoiceId", invoiceId),
                InvoiceRepository::mapPaymentRow));
    }

    /**
     * Lists all clinic payments.
     */
    public List<InvoiceModels.PaymentRow> listPayments(ClinicScope scope) {
        return scope.read(() -> jdbc.query("""
                select cp."id", cp."organizationId", cp."projectId", cp."invoiceId",
                       ci."invoiceNumber", cp."patientId", p."displayName" as "patientName",
                       cp."paymentNumber", cp."amount", cp."currency", cp."paymentMethod",
                       cp."referenceNumber", cp."notes", cp."recordedByUserId",
                       u."fullName" as "recordedByUserName", cp."paymentDate", cp."createdAt"
                  from "clinic_payments" cp
                  inner join "clinic_invoices" ci on ci."id" = cp."invoiceId"
                  inner join "patients" p on p."id" = cp."patientId"
                  inner join "users" u on u."id" = cp."recordedByUserId"
                 where %s
                 order by cp."paymentDate" desc
                """.formatted(scope.predicate("cp")),
                scope.bind(),
                InvoiceRepository::mapPaymentRow));
    }

    /**
     * Finds payment receipt details by payment ID (BIL-06).
     */
    public Optional<InvoiceModels.PaymentReceipt> findPaymentReceipt(ClinicScope scope, UUID paymentId) {
        return scope.read(() -> {
            try {
                return Optional.ofNullable(jdbc.queryForObject("""
                        select cp."id" as "paymentId", cp."paymentNumber", cp."invoiceId",
                               ci."invoiceNumber", o."name" as "clinicName",
                               cp."patientId", p."displayName" as "patientName", p."patientNo",
                               cp."amount", cp."currency", cp."paymentMethod",
                               cp."referenceNumber", cp."notes", cp."paymentDate",
                               u."fullName" as "recordedByUserName",
                               ci."balanceDue" as "remainingInvoiceBalance"
                          from "clinic_payments" cp
                          inner join "clinic_invoices" ci on ci."id" = cp."invoiceId"
                          inner join "organizations" o on o."id" = cp."organizationId"
                          inner join "patients" p on p."id" = cp."patientId"
                          inner join "users" u on u."id" = cp."recordedByUserId"
                         where %s
                           and cp."id" = :paymentId
                        """.formatted(scope.predicate("cp")),
                        scope.bind().addValue("paymentId", paymentId),
                        (rs, rowNum) -> new InvoiceModels.PaymentReceipt(
                                (UUID) rs.getObject("paymentId"),
                                rs.getString("paymentNumber"),
                                (UUID) rs.getObject("invoiceId"),
                                rs.getString("invoiceNumber"),
                                rs.getString("clinicName"),
                                (UUID) rs.getObject("patientId"),
                                rs.getString("patientName"),
                                rs.getString("patientNo"),
                                rs.getBigDecimal("amount"),
                                rs.getString("currency"),
                                rs.getString("paymentMethod"),
                                rs.getString("referenceNumber"),
                                rs.getString("notes"),
                                rs.getTimestamp("paymentDate").toLocalDateTime(),
                                rs.getString("recordedByUserName"),
                                rs.getBigDecimal("remainingInvoiceBalance")
                        )));
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Applies a discount to an invoice (BIL-04).
     */
    public void applyDiscount(
            ClinicScope scope,
            UUID invoiceId,
            BigDecimal discountAmount,
            String discountReason,
            UUID discountAppliedBy) {
        scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("invoiceId", invoiceId)
                    .addValue("discountAmount", discountAmount)
                    .addValue("discountReason", discountReason)
                    .addValue("discountAppliedBy", discountAppliedBy);

            jdbc.update("""
                    update clinic_invoices
                       set "discountAmount" = :discountAmount,
                           "discountReason" = :discountReason,
                           "discountAppliedBy" = :discountAppliedBy,
                           "totalAmount" = greatest("subtotal" - :discountAmount, 0.000),
                           "balanceDue" = greatest(greatest("subtotal" - :discountAmount, 0.000) - "paidAmount", 0.000),
                           "status" = case
                               when greatest("subtotal" - :discountAmount, 0.000) <= "paidAmount" then 'PAID'
                               when "paidAmount" > 0 then 'PARTIALLY_PAID'
                               else 'ISSUED'
                           end,
                           "updatedAt" = (now() at time zone 'UTC')
                     where %s
                       and id = :invoiceId
                       and "status" != 'CANCELLED'
                    """.formatted(scope.predicate("clinic_invoices")), params);
            return null;
        });
    }

    /**
     * Cancels an invoice with a mandatory cancellation reason (BIL-05).
     */
    public void cancelInvoice(ClinicScope scope, UUID invoiceId, UUID cancelledBy, String cancellationReason) {
        scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("invoiceId", invoiceId)
                    .addValue("cancelledBy", cancelledBy)
                    .addValue("cancellationReason", cancellationReason);

            jdbc.update("""
                    update clinic_invoices
                       set "status" = 'CANCELLED',
                           "cancelledAt" = (now() at time zone 'UTC'),
                           "cancelledBy" = :cancelledBy,
                           "cancellationReason" = :cancellationReason,
                           "balanceDue" = 0.000,
                           "updatedAt" = (now() at time zone 'UTC')
                     where %s
                       and id = :invoiceId
                    """.formatted(scope.predicate("clinic_invoices")), params);
            return null;
        });
    }

    /**
     * Recalculates and updates the invoice paidAmount, balanceDue, and status (accounting for refunds).
     */
    public void recalculateInvoicePaymentTotals(ClinicScope scope, UUID invoiceId) {
        scope.read(() -> {
            MapSqlParameterSource params = scope.bind().addValue("invoiceId", invoiceId);

            jdbc.update("""
                    update clinic_invoices ci
                       set "paidAmount" = greatest(coalesce(p.total_paid, 0.000) - coalesce(r.total_refunded, 0.000), 0.000),
                           "balanceDue" = greatest(ci."totalAmount" - greatest(coalesce(p.total_paid, 0.000) - coalesce(r.total_refunded, 0.000), 0.000), 0.000),
                           "status" = case
                               when ci."status" = 'CANCELLED' then 'CANCELLED'
                               when greatest(ci."totalAmount" - greatest(coalesce(p.total_paid, 0.000) - coalesce(r.total_refunded, 0.000), 0.000), 0.000) = 0.000 then 'PAID'
                               when (coalesce(p.total_paid, 0.000) - coalesce(r.total_refunded, 0.000)) > 0 then 'PARTIALLY_PAID'
                               else 'ISSUED'
                           end,
                           "updatedAt" = (now() at time zone 'UTC')
                      from (
                          select coalesce(sum("amount"), 0.000) as total_paid
                            from "clinic_payments"
                           where "invoiceId" = :invoiceId
                      ) p
                      cross join (
                          select coalesce(sum("amount"), 0.000) as total_refunded
                            from "clinic_refunds"
                           where "invoiceId" = :invoiceId
                      ) r
                     where %s
                       and ci.id = :invoiceId
                       and ci."status" != 'CANCELLED'
                    """.formatted(scope.predicate("ci")), params);
            return null;
        });
    }

    /**
     * Updates invoiced amount on a treatment plan item.
     */
    public void incrementPlanItemInvoiced(ClinicScope scope, UUID treatmentPlanItemId, BigDecimal amount) {
        scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("itemId", treatmentPlanItemId)
                    .addValue("amount", amount);

            jdbc.update("""
                    update treatment_plan_items
                       set "invoicedAmount" = "invoicedAmount" + :amount,
                           "updatedAt" = (now() at time zone 'UTC')
                     where %s
                       and id = :itemId
                    """.formatted(scope.predicate("treatment_plan_items")), params);
            return null;
        });
    }

    /**
     * Updates collected amount on a treatment plan item.
     */
    public void incrementPlanItemCollected(ClinicScope scope, UUID treatmentPlanItemId, BigDecimal amount) {
        scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("itemId", treatmentPlanItemId)
                    .addValue("amount", amount);

            jdbc.update("""
                    update treatment_plan_items
                       set "collectedAmount" = "collectedAmount" + :amount,
                           "updatedAt" = (now() at time zone 'UTC')
                     where %s
                       and id = :itemId
                    """.formatted(scope.predicate("treatment_plan_items")), params);
            return null;
        });
    }

    /**
     * Inserts a refund or payment reversal record.
     */
    public UUID insertRefund(
            ClinicScope scope,
            UUID invoiceId,
            UUID paymentId,
            UUID patientId,
            String refundNumber,
            BigDecimal amount,
            String currency,
            String refundMethod,
            String reason,
            UUID approvedByUserId) {
        UUID refundId = UUID.randomUUID();
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", refundId)
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("invoiceId", invoiceId)
                    .addValue("paymentId", paymentId)
                    .addValue("patientId", patientId)
                    .addValue("refundNumber", refundNumber)
                    .addValue("amount", amount)
                    .addValue("currency", currency)
                    .addValue("refundMethod", refundMethod)
                    .addValue("reason", reason)
                    .addValue("approvedByUserId", approvedByUserId);

            jdbc.update("""
                    insert into clinic_refunds (
                        id, "organizationId", "projectId", "invoiceId", "paymentId", "patientId",
                        "refundNumber", "amount", "currency", "refundMethod", "reason", "approvedByUserId"
                    ) values (
                        :id, :orgId, :projId, :invoiceId, :paymentId, :patientId,
                        :refundNumber, :amount, :currency, :refundMethod, :reason, :approvedByUserId
                    )
                    """, params);
            return refundId;
        });
    }

    /**
     * Finds a refund by ID.
     */
    public Optional<InvoiceModels.RefundRow> findRefund(ClinicScope scope, UUID refundId) {
        return scope.read(() -> {
            try {
                return Optional.ofNullable(jdbc.queryForObject("""
                        select cr."id", cr."organizationId", cr."projectId", cr."invoiceId",
                               ci."invoiceNumber", cr."paymentId", cp."paymentNumber",
                               cr."patientId", p."displayName" as "patientName",
                               cr."refundNumber", cr."amount", cr."currency", cr."refundMethod",
                               cr."reason", cr."approvedByUserId", u."fullName" as "approvedByUserName",
                               cr."refundDate", cr."createdAt"
                          from "clinic_refunds" cr
                          inner join "clinic_invoices" ci on ci."id" = cr."invoiceId"
                          left join "clinic_payments" cp on cp."id" = cr."paymentId"
                          inner join "patients" p on p."id" = cr."patientId"
                          inner join "users" u on u."id" = cr."approvedByUserId"
                         where %s
                           and cr."id" = :refundId
                        """.formatted(scope.predicate("cr")),
                        scope.bind().addValue("refundId", refundId),
                        InvoiceRepository::mapRefundRow));
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Lists all refunds recorded for a given invoice.
     */
    public List<InvoiceModels.RefundRow> listRefundsForInvoice(ClinicScope scope, UUID invoiceId) {
        return scope.read(() -> jdbc.query("""
                select cr."id", cr."organizationId", cr."projectId", cr."invoiceId",
                       ci."invoiceNumber", cr."paymentId", cp."paymentNumber",
                       cr."patientId", p."displayName" as "patientName",
                       cr."refundNumber", cr."amount", cr."currency", cr."refundMethod",
                       cr."reason", cr."approvedByUserId", u."fullName" as "approvedByUserName",
                       cr."refundDate", cr."createdAt"
                  from "clinic_refunds" cr
                  inner join "clinic_invoices" ci on ci."id" = cr."invoiceId"
                  left join "clinic_payments" cp on cp."id" = cr."paymentId"
                  inner join "patients" p on p."id" = cr."patientId"
                  inner join "users" u on u."id" = cr."approvedByUserId"
                 where %s
                   and cr."invoiceId" = :invoiceId
                 order by cr."refundDate" desc
                """.formatted(scope.predicate("cr")),
                scope.bind().addValue("invoiceId", invoiceId),
                InvoiceRepository::mapRefundRow));
    }

    /**
     * Inserts an immutable dual-entry patient ledger entry.
     */
    public UUID insertLedgerEntry(
            ClinicScope scope,
            UUID patientId,
            String entryType,
            String direction,
            BigDecimal amount,
            String currency,
            UUID invoiceId,
            UUID paymentId,
            UUID refundId,
            String referenceNumber,
            String description,
            UUID recordedByUserId) {
        UUID entryId = UUID.randomUUID();
        return scope.read(() -> {
            MapSqlParameterSource params = scope.bind()
                    .addValue("id", entryId)
                    .addValue("orgId", scope.queryScope().organizationId())
                    .addValue("projId", scope.projectId())
                    .addValue("patientId", patientId)
                    .addValue("entryType", entryType)
                    .addValue("direction", direction)
                    .addValue("amount", amount)
                    .addValue("currency", currency)
                    .addValue("invoiceId", invoiceId)
                    .addValue("paymentId", paymentId)
                    .addValue("refundId", refundId)
                    .addValue("referenceNumber", referenceNumber)
                    .addValue("description", description)
                    .addValue("recordedByUserId", recordedByUserId);

            jdbc.update("""
                    insert into clinic_patient_ledger_entries (
                        id, "organizationId", "projectId", "patientId",
                        "entryType", "direction", "amount", "currency",
                        "invoiceId", "paymentId", "refundId",
                        "referenceNumber", "description", "recordedByUserId"
                    ) values (
                        :id, :orgId, :projId, :patientId,
                        :entryType, :direction, :amount, :currency,
                        :invoiceId, :paymentId, :refundId,
                        :referenceNumber, :description, :recordedByUserId
                    )
                    """, params);
            return entryId;
        });
    }

    /**
     * Lists patient ledger entries chronologically.
     */
    public List<InvoiceModels.PatientLedgerEntryRow> listPatientLedger(
            ClinicScope scope,
            UUID patientId,
            LocalDateTime fromDate,
            LocalDateTime toDate) {
        return scope.read(() -> {
            String dateFilter = "";
            MapSqlParameterSource params = scope.bind().addValue("patientId", patientId);
            if (fromDate != null) {
                dateFilter += " and le.\"entryDate\" >= :fromDate ";
                params.addValue("fromDate", fromDate);
            }
            if (toDate != null) {
                dateFilter += " and le.\"entryDate\" <= :toDate ";
                params.addValue("toDate", toDate);
            }

            return jdbc.query("""
                    select le."id", le."organizationId", le."projectId", le."patientId",
                           le."entryType", le."direction", le."amount", le."currency",
                           le."invoiceId", ci."invoiceNumber",
                           le."paymentId", cp."paymentNumber",
                           le."refundId", cr."refundNumber",
                           le."referenceNumber", le."description",
                           le."recordedByUserId", u."fullName" as "recordedByUserName",
                           le."entryDate", le."createdAt"
                      from "clinic_patient_ledger_entries" le
                      left join "clinic_invoices" ci on ci."id" = le."invoiceId"
                      left join "clinic_payments" cp on cp."id" = le."paymentId"
                      left join "clinic_refunds" cr on cr."id" = le."refundId"
                      inner join "users" u on u."id" = le."recordedByUserId"
                     where %s
                       and le."patientId" = :patientId
                       %s
                     order by le."entryDate" asc, le."createdAt" asc
                    """.formatted(scope.predicate("le"), dateFilter),
                    params,
                    InvoiceRepository::mapLedgerEntryRow);
        });
    }

    /**
     * Produces a consolidated statement of account for a patient.
     */
    public InvoiceModels.PatientStatement getPatientStatement(
            ClinicScope scope,
            UUID patientId,
            LocalDateTime fromDate,
            LocalDateTime toDate) {
        return scope.read(() -> {
            var pat = jdbc.queryForMap("""
                    select p."displayName", p."patientNo"
                      from "patients" p
                     where %s and p.id = :patientId
                    """.formatted(scope.predicate("p")),
                    scope.bind().addValue("patientId", patientId));

            String patientName = (String) pat.get("displayName");
            String patientNo = (String) pat.get("patientNo");

            List<InvoiceModels.PatientLedgerEntryRow> entries = listPatientLedger(scope, patientId, fromDate, toDate);

            BigDecimal totalDebits = BigDecimal.ZERO;
            BigDecimal totalCredits = BigDecimal.ZERO;

            for (InvoiceModels.PatientLedgerEntryRow entry : entries) {
                if ("DEBIT".equalsIgnoreCase(entry.direction())) {
                    totalDebits = totalDebits.add(entry.amount());
                } else if ("CREDIT".equalsIgnoreCase(entry.direction())) {
                    totalCredits = totalCredits.add(entry.amount());
                }
            }

            BigDecimal netBalanceDue = totalDebits.subtract(totalCredits);

            return new InvoiceModels.PatientStatement(
                    patientId,
                    patientName,
                    patientNo,
                    "JOD",
                    fromDate,
                    toDate,
                    totalDebits,
                    totalCredits,
                    netBalanceDue,
                    entries
            );
        });
    }

    /**
     * DTO for treatment plan items eligible for billing.
     */
    public record TreatmentPlanItemBillingDto(
            UUID id,
            UUID planId,
            UUID procedureId,
            String procedureName,
            Integer toothFdi,
            BigDecimal estimatedCost,
            BigDecimal invoicedAmount,
            BigDecimal remainingUninvoiced,
            String status) {
    }

    /**
     * Queries treatment plan items eligible for billing.
     */
    public List<TreatmentPlanItemBillingDto> listPlanItemsForBilling(ClinicScope scope, UUID planId, List<UUID> itemIds) {
        return scope.read(() -> {
            String filter = "";
            MapSqlParameterSource params = scope.bind().addValue("planId", planId);
            if (itemIds != null && !itemIds.isEmpty()) {
                filter = " and tpi.id in (:itemIds) ";
                params.addValue("itemIds", itemIds);
            }

            return jdbc.query("""
                    select tpi.id, tpi."planId", tpi."procedureId", tpi."procedureName",
                           tpi."toothFdi", tpi."estimatedCost", tpi."invoicedAmount",
                           greatest(tpi."estimatedCost" - tpi."invoicedAmount", 0.000) as remaining_uninvoiced,
                           tpi.status
                      from "treatment_plan_items" tpi
                     where %s
                       and tpi."planId" = :planId
                       and tpi.status not in ('CANCELLED')
                       %s
                     order by tpi."sequenceOrder" asc
                    """.formatted(scope.predicate("tpi"), filter),
                    params,
                    (rs, rowNum) -> new TreatmentPlanItemBillingDto(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("planId"),
                            (UUID) rs.getObject("procedureId"),
                            rs.getString("procedureName"),
                            rs.getObject("toothFdi") != null ? rs.getInt("toothFdi") : null,
                            rs.getBigDecimal("estimatedCost"),
                            rs.getBigDecimal("invoicedAmount"),
                            rs.getBigDecimal("remaining_uninvoiced"),
                            rs.getString("status")
                    ));
        });
    }

    private static InvoiceModels.InvoiceHeader mapInvoiceHeader(ResultSet rs, int rowNum) throws SQLException {
        return new InvoiceModels.InvoiceHeader(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                (UUID) rs.getObject("patientId"),
                rs.getString("patientName"),
                rs.getString("patientNo"),
                (UUID) rs.getObject("encounterId"),
                (UUID) rs.getObject("treatmentPlanId"),
                rs.getString("treatmentPlanTitle"),
                rs.getString("invoiceNumber"),
                rs.getString("currency"),
                rs.getBigDecimal("subtotal"),
                rs.getBigDecimal("discountAmount"),
                rs.getString("discountReason"),
                (UUID) rs.getObject("discountAppliedBy"),
                rs.getString("discountAppliedByName"),
                rs.getBigDecimal("totalAmount"),
                rs.getBigDecimal("paidAmount"),
                rs.getBigDecimal("balanceDue"),
                rs.getString("status"),
                rs.getString("notes"),
                rs.getTimestamp("issuedAt").toLocalDateTime(),
                rs.getTimestamp("cancelledAt") != null ? rs.getTimestamp("cancelledAt").toLocalDateTime() : null,
                (UUID) rs.getObject("cancelledBy"),
                rs.getString("cancelledByName"),
                rs.getString("cancellationReason"),
                rs.getInt("itemCount"),
                rs.getInt("paymentCount"),
                rs.getTimestamp("createdAt").toLocalDateTime(),
                rs.getTimestamp("updatedAt").toLocalDateTime()
        );
    }

    private static InvoiceModels.InvoiceItemRow mapInvoiceItemRow(ResultSet rs, int rowNum) throws SQLException {
        return new InvoiceModels.InvoiceItemRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                (UUID) rs.getObject("invoiceId"),
                (UUID) rs.getObject("treatmentPlanItemId"),
                (UUID) rs.getObject("procedureId"),
                rs.getString("procedureName"),
                rs.getString("itemType"),
                rs.getString("description"),
                rs.getObject("toothFdi") != null ? rs.getInt("toothFdi") : null,
                rs.getBigDecimal("unitPrice"),
                rs.getInt("quantity"),
                rs.getBigDecimal("totalPrice"),
                rs.getTimestamp("createdAt").toLocalDateTime()
        );
    }

    private static InvoiceModels.PaymentRow mapPaymentRow(ResultSet rs, int rowNum) throws SQLException {
        return new InvoiceModels.PaymentRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                (UUID) rs.getObject("invoiceId"),
                rs.getString("invoiceNumber"),
                (UUID) rs.getObject("patientId"),
                rs.getString("patientName"),
                rs.getString("paymentNumber"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("paymentMethod"),
                rs.getString("referenceNumber"),
                rs.getString("notes"),
                (UUID) rs.getObject("recordedByUserId"),
                rs.getString("recordedByUserName"),
                rs.getTimestamp("paymentDate").toLocalDateTime(),
                rs.getTimestamp("createdAt").toLocalDateTime()
        );
    }

    private static InvoiceModels.PaymentReceipt mapPaymentReceipt(ResultSet rs, int rowNum) throws SQLException {
        return new InvoiceModels.PaymentReceipt(
                (UUID) rs.getObject("paymentId"),
                rs.getString("paymentNumber"),
                (UUID) rs.getObject("invoiceId"),
                rs.getString("invoiceNumber"),
                rs.getString("clinicName"),
                (UUID) rs.getObject("patientId"),
                rs.getString("patientName"),
                rs.getString("patientNo"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("paymentMethod"),
                rs.getString("referenceNumber"),
                rs.getString("notes"),
                rs.getTimestamp("paymentDate").toLocalDateTime(),
                rs.getString("recordedByUserName"),
                rs.getBigDecimal("remainingInvoiceBalance")
        );
    }

    private static InvoiceModels.RefundRow mapRefundRow(ResultSet rs, int rowNum) throws SQLException {
        return new InvoiceModels.RefundRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                (UUID) rs.getObject("invoiceId"),
                rs.getString("invoiceNumber"),
                (UUID) rs.getObject("paymentId"),
                rs.getString("paymentNumber"),
                (UUID) rs.getObject("patientId"),
                rs.getString("patientName"),
                rs.getString("refundNumber"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("refundMethod"),
                rs.getString("reason"),
                (UUID) rs.getObject("approvedByUserId"),
                rs.getString("approvedByUserName"),
                rs.getTimestamp("refundDate").toLocalDateTime(),
                rs.getTimestamp("createdAt").toLocalDateTime()
        );
    }

    private static InvoiceModels.PatientLedgerEntryRow mapLedgerEntryRow(ResultSet rs, int rowNum) throws SQLException {
        return new InvoiceModels.PatientLedgerEntryRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organizationId"),
                (UUID) rs.getObject("projectId"),
                (UUID) rs.getObject("patientId"),
                rs.getString("entryType"),
                rs.getString("direction"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                (UUID) rs.getObject("invoiceId"),
                rs.getString("invoiceNumber"),
                (UUID) rs.getObject("paymentId"),
                rs.getString("paymentNumber"),
                (UUID) rs.getObject("refundId"),
                rs.getString("refundNumber"),
                rs.getString("referenceNumber"),
                rs.getString("description"),
                (UUID) rs.getObject("recordedByUserId"),
                rs.getString("recordedByUserName"),
                rs.getTimestamp("entryDate").toLocalDateTime(),
                rs.getTimestamp("createdAt").toLocalDateTime()
        );
    }
}
