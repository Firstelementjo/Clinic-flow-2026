package com.firstelement.crm.clinic.operations;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Persistence operations for Branch Inventory, Service BOM, and Auto-Deduction Ledger (Req 1). */
final class InventoryOperationsStore {

    private final NamedParameterJdbcTemplate jdbc;

    InventoryOperationsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ClinicOperationsModels.InventoryItemRow> listItems(
            ClinicScope scope, UUID clinicId, String q, boolean lowStockOnly) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select ii.id, ii."organizationId", ii."projectId", ii."clinicId",
                           coalesce(cl."name", 'General Branch') as "clinicName",
                           ii."name", ii."sku", ii."category", ii."unitOfMeasure",
                           ii."unitCost", ii."quantityOnHand", ii."minStockThreshold",
                           ii."isActive", ii."createdAt", ii."updatedAt"
                      from inventory_items ii
                      left join clinic_locations cl
                             on cl."id" = ii."clinicId"
                            and cl."organizationId" = ii."organizationId"
                     where %s
                    """.formatted(scope.predicate("ii")));
            MapSqlParameterSource params = scope.bind();
            if (clinicId != null) {
                sql.append(" and ii.\"clinicId\" = :clinicId");
                params.addValue("clinicId", clinicId);
            }
            if (q != null && !q.isBlank()) {
                sql.append(" and (ii.\"name\" ilike :q or coalesce(ii.\"sku\", '') ilike :q or ii.\"category\" ilike :q)");
                params.addValue("q", "%" + q.trim() + "%");
            }
            if (lowStockOnly) {
                sql.append(" and ii.\"quantityOnHand\" <= ii.\"minStockThreshold\"");
            }
            sql.append(" order by ii.\"isActive\" desc, ii.\"name\" asc");
            return jdbc.query(sql.toString(), params, InventoryOperationsStore::mapItem);
        });
    }

    List<ClinicOperationsModels.ConsolidatedInventoryRow> listConsolidated(ClinicScope scope, String q) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select ii."name" as "itemName",
                           max(ii."sku") as "sku",
                           ii."category",
                           ii."unitOfMeasure",
                           coalesce(sum(ii."quantityOnHand"), 0) as "totalQuantityOnHand",
                           count(distinct coalesce(ii."clinicId", ii."projectId")) as "branchesCount",
                           count(*) filter (where ii."quantityOnHand" <= ii."minStockThreshold") as "lowStockBranchesCount",
                           coalesce(sum(ii."quantityOnHand" * ii."unitCost"), 0.000) as "totalValuation"
                      from inventory_items ii
                     where %s and ii."isActive" = true
                    """.formatted(scope.predicate("ii")));
            MapSqlParameterSource params = scope.bind();
            if (q != null && !q.isBlank()) {
                sql.append(" and (ii.\"name\" ilike :q or coalesce(ii.\"sku\", '') ilike :q or ii.\"category\" ilike :q)");
                params.addValue("q", "%" + q.trim() + "%");
            }
            sql.append(" group by ii.\"name\", ii.\"category\", ii.\"unitOfMeasure\" order by ii.\"name\" asc");
            return jdbc.query(sql.toString(), params, (rs, n) -> new ClinicOperationsModels.ConsolidatedInventoryRow(
                    rs.getString("itemName"),
                    rs.getString("sku"),
                    rs.getString("category"),
                    rs.getString("unitOfMeasure"),
                    rs.getInt("totalQuantityOnHand"),
                    rs.getInt("branchesCount"),
                    rs.getInt("lowStockBranchesCount"),
                    scaled(rs.getBigDecimal("totalValuation"))));
        });
    }

    ClinicOperationsModels.InventoryItemRow createItem(
            ClinicScope scope, ClinicOperationsModels.CreateInventoryItemRequest req, UUID performedByUserId) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("An inventory item must belong to one branch project.");
        }
        int qty = req.quantityOnHand() != null ? Math.max(0, req.quantityOnHand()) : 0;
        int minThreshold = req.minStockThreshold() != null ? Math.max(0, req.minStockThreshold()) : 5;
        BigDecimal cost = req.unitCost() != null ? scaled(req.unitCost()) : BigDecimal.ZERO.setScale(3);
        if (cost.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationFailedException("unitCost cannot be negative.");
        }
        String category = (req.category() != null && !req.category().isBlank()) ? req.category().trim().toUpperCase() : "CONSUMABLES";
        String uom = (req.unitOfMeasure() != null && !req.unitOfMeasure().isBlank()) ? req.unitOfMeasure().trim() : "UNIT";

        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into inventory_items
                        (id, "organizationId", "projectId", "clinicId", "name", "sku", "category",
                         "unitOfMeasure", "unitCost", "quantityOnHand", "minStockThreshold", "isActive")
                    values
                        (:id, :orgId, :projId, :clinicId, :name, :sku, :category,
                         :uom, :unitCost, :qty, :minThreshold, true)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("orgId", scope.queryScope().organizationId())
                            .addValue("projId", scope.projectId())
                            .addValue("clinicId", req.clinicId())
                            .addValue("name", req.name().trim())
                            .addValue("sku", trimToNull(req.sku()))
                            .addValue("category", category)
                            .addValue("uom", uom)
                            .addValue("unitCost", cost)
                            .addValue("qty", qty)
                            .addValue("minThreshold", minThreshold));

            if (qty > 0) {
                boolean isPurchase = Boolean.TRUE.equals(req.recordPurchaseExpense());
                String txType = isPurchase ? "MANUAL_PURCHASE" : "MANUAL_ADJUSTMENT";
                UUID txId = insertTransaction(scope, req.clinicId(), id, txType, qty, 0, qty, 0,
                        cost, "INITIAL_STOCK", id, performedByUserId,
                        isPurchase ? "Initial stock purchase" : "Initial stock entry");
                if (isPurchase && cost.compareTo(BigDecimal.ZERO) > 0) {
                    recordInventoryPurchaseExpense(scope, req.clinicId(), req.name().trim(),
                            cost.multiply(BigDecimal.valueOf(qty)), txId, performedByUserId);
                }
            }
            return findItem(scope, id);
        });
    }

    ClinicOperationsModels.InventoryItemRow updateItem(
            ClinicScope scope, UUID itemId, ClinicOperationsModels.UpdateInventoryItemRequest req) {
        return scope.read(() -> {
            ClinicOperationsModels.InventoryItemRow existing = findItem(scope, itemId);
            String name = (req.name() != null && !req.name().isBlank()) ? req.name().trim() : existing.name();
            String sku = req.sku() != null ? trimToNull(req.sku()) : existing.sku();
            String category = (req.category() != null && !req.category().isBlank())
                    ? req.category().trim().toUpperCase()
                    : existing.category();
            String uom = (req.unitOfMeasure() != null && !req.unitOfMeasure().isBlank())
                    ? req.unitOfMeasure().trim()
                    : existing.unitOfMeasure();
            BigDecimal cost = req.unitCost() != null ? scaled(req.unitCost()) : existing.unitCost();
            if (cost.compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationFailedException("unitCost cannot be negative.");
            }
            int minThreshold = req.minStockThreshold() != null ? Math.max(0, req.minStockThreshold()) : existing.minStockThreshold();
            UUID clinicId = req.clinicId() != null ? req.clinicId() : existing.clinicId();
            boolean isActive = req.isActive() != null ? req.isActive() : existing.isActive();

            jdbc.update("""
                    update inventory_items ii
                       set "name" = :name,
                           "sku" = :sku,
                           "category" = :category,
                           "unitOfMeasure" = :uom,
                           "unitCost" = :cost,
                           "minStockThreshold" = :minThreshold,
                           "clinicId" = :clinicId,
                           "isActive" = :isActive,
                           "updatedAt" = (now() at time zone 'utc')
                     where %s and ii.id = :id
                    """.formatted(scope.predicate("ii")),
                    scope.bind()
                            .addValue("id", itemId)
                            .addValue("name", name)
                            .addValue("sku", sku)
                            .addValue("category", category)
                            .addValue("uom", uom)
                            .addValue("cost", cost)
                            .addValue("minThreshold", minThreshold)
                            .addValue("clinicId", clinicId)
                            .addValue("isActive", isActive));
            return findItem(scope, itemId);
        });
    }

    ClinicOperationsModels.InventoryItemRow adjustStock(
            ClinicScope scope, UUID itemId, ClinicOperationsModels.AdjustInventoryStockRequest req, UUID performedByUserId) {
        if (req.quantity() == 0) {
            throw new ValidationFailedException("Adjustment quantity cannot be zero.");
        }
        String txType = (req.transactionType() != null && !req.transactionType().isBlank())
                ? req.transactionType().trim().toUpperCase()
                : (req.quantity() > 0 ? "MANUAL_PURCHASE" : "MANUAL_ADJUSTMENT");
        if (!"MANUAL_PURCHASE".equals(txType) && !"MANUAL_ADJUSTMENT".equals(txType)) {
            throw new ValidationFailedException("transactionType must be MANUAL_PURCHASE or MANUAL_ADJUSTMENT.");
        }
        return scope.read(() -> {
            ClinicOperationsModels.InventoryItemRow item = findItem(scope, itemId);
            int before = item.quantityOnHand();
            int after = before + req.quantity();
            if (after < 0) {
                throw new ValidationFailedException("Stock quantity cannot become negative (current: " + before + ").");
            }
            BigDecimal cost = req.unitCost() != null ? scaled(req.unitCost()) : item.unitCost();
            if (cost.compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationFailedException("unitCost cannot be negative.");
            }
            jdbc.update("""
                    update inventory_items ii
                       set "quantityOnHand" = :after,
                           "unitCost" = :cost,
                           "updatedAt" = (now() at time zone 'utc')
                     where %s and ii.id = :id
                    """.formatted(scope.predicate("ii")),
                    scope.bind().addValue("id", itemId).addValue("after", after).addValue("cost", cost));

            UUID txId = insertTransaction(scope, item.clinicId(), itemId, txType, req.quantity(), before, after,
                    0, cost, "MANUAL_STOCK", itemId, performedByUserId, trimToNull(req.notes()));

            if (Boolean.TRUE.equals(req.recordPurchaseExpense()) && req.quantity() > 0 && cost.compareTo(BigDecimal.ZERO) > 0) {
                recordInventoryPurchaseExpense(scope, item.clinicId(), item.name(),
                        cost.multiply(BigDecimal.valueOf(req.quantity())), txId, performedByUserId);
            }
            return findItem(scope, itemId);
        });
    }

    boolean deleteItem(ClinicScope scope, UUID itemId) {
        return scope.read(() -> {
            jdbc.update("""
                    delete from service_materials sm
                     where %s and sm."itemId" = :itemId
                    """.formatted(scope.predicate("sm")),
                    scope.bind().addValue("itemId", itemId));

            Integer txCount = jdbc.queryForObject("""
                    select count(*) from inventory_transactions it
                     where %s and it."itemId" = :itemId
                    """.formatted(scope.predicate("it")),
                    scope.bind().addValue("itemId", itemId),
                    Integer.class);
            if (txCount != null && txCount > 0) {
                return jdbc.update("""
                        update inventory_items ii
                           set "isActive" = false, "updatedAt" = (now() at time zone 'utc')
                         where %s and ii.id = :id
                        """.formatted(scope.predicate("ii")),
                        scope.bind().addValue("id", itemId)) > 0;
            }
            return jdbc.update("""
                    delete from inventory_items ii
                     where %s and ii.id = :id
                    """.formatted(scope.predicate("ii")),
                    scope.bind().addValue("id", itemId)) > 0;
        });
    }

    List<ClinicOperationsModels.InventoryTransactionRow> listTransactions(
            ClinicScope scope, UUID itemId, String transactionType, int limit) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select it.id, it."organizationId", it."projectId", it."clinicId",
                           coalesce(cl."name", 'General Branch') as "clinicName",
                           it."itemId", coalesce(ii."name", 'Deleted Item') as "itemName",
                           it."transactionType", it."quantity", it."quantityBefore", it."quantityAfter",
                           it."shortfallQuantity", it."unitCost", it."referenceType", it."referenceId",
                           it."notes", it."createdAt"
                      from inventory_transactions it
                      left join inventory_items ii on ii.id = it."itemId" and ii."organizationId" = it."organizationId"
                      left join clinic_locations cl on cl.id = it."clinicId" and cl."organizationId" = it."organizationId"
                     where %s
                    """.formatted(scope.predicate("it")));
            MapSqlParameterSource params = scope.bind().addValue("limit", Math.min(Math.max(limit, 1), 500));
            if (itemId != null) {
                sql.append(" and it.\"itemId\" = :itemId");
                params.addValue("itemId", itemId);
            }
            if (transactionType != null && !transactionType.isBlank()) {
                sql.append(" and it.\"transactionType\" = :txType");
                params.addValue("txType", transactionType.trim().toUpperCase());
            }
            sql.append(" order by it.\"createdAt\" desc limit :limit");
            return jdbc.query(sql.toString(), params, (rs, n) -> new ClinicOperationsModels.InventoryTransactionRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organizationId", UUID.class),
                    rs.getObject("projectId", UUID.class),
                    rs.getObject("clinicId", UUID.class),
                    rs.getString("clinicName"),
                    rs.getObject("itemId", UUID.class),
                    rs.getString("itemName"),
                    rs.getString("transactionType"),
                    rs.getInt("quantity"),
                    rs.getInt("quantityBefore"),
                    rs.getInt("quantityAfter"),
                    rs.getInt("shortfallQuantity"),
                    scaled(rs.getBigDecimal("unitCost")),
                    rs.getString("referenceType"),
                    rs.getObject("referenceId", UUID.class),
                    rs.getString("notes"),
                    toLocalDateTime(rs.getTimestamp("createdAt"))));
        });
    }

    List<ClinicOperationsModels.ServiceMaterialRow> listServiceMaterials(ClinicScope scope, UUID procedureId) {
        return scope.read(() -> jdbc.query("""
                select sm.id, sm."procedureId", coalesce(p."name", 'Service') as "procedureName",
                       sm."itemId", ii."name" as "itemName", ii."unitOfMeasure",
                       ii."unitCost", sm."quantityRequired", ii."quantityOnHand"
                  from service_materials sm
                  join inventory_items ii on ii.id = sm."itemId" and ii."organizationId" = sm."organizationId"
                  left join procedures p on p.id = sm."procedureId" and p."organizationId" = sm."organizationId"
                 where %s and sm."procedureId" = :procedureId
                 order by ii."name" asc
                """.formatted(scope.predicate("sm")),
                scope.bind().addValue("procedureId", procedureId),
                (rs, n) -> new ClinicOperationsModels.ServiceMaterialRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("procedureId", UUID.class),
                        rs.getString("procedureName"),
                        rs.getObject("itemId", UUID.class),
                        rs.getString("itemName"),
                        rs.getString("unitOfMeasure"),
                        scaled(rs.getBigDecimal("unitCost")),
                        rs.getInt("quantityRequired"),
                        rs.getInt("quantityOnHand"))));
    }

    List<ClinicOperationsModels.ServiceMaterialRow> replaceServiceMaterials(
            ClinicScope scope, UUID procedureId, List<ClinicOperationsModels.ServiceMaterialInput> materials) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("Service materials must be saved within one branch project.");
        }
        return scope.read(() -> {
            Integer procCount = jdbc.queryForObject("""
                    select count(*) from procedures p
                     where %s and p.id = :procedureId
                    """.formatted(scope.predicate("p")),
                    scope.bind().addValue("procedureId", procedureId),
                    Integer.class);
            if (procCount == null || procCount == 0) {
                throw new ResourceNotFoundException("Procedure not found");
            }

            jdbc.update("""
                    delete from service_materials sm
                     where %s and sm."procedureId" = :procedureId
                    """.formatted(scope.predicate("sm")),
                    scope.bind().addValue("procedureId", procedureId));

            if (materials != null && !materials.isEmpty()) {
                Map<UUID, Integer> merged = new LinkedHashMap<>();
                for (ClinicOperationsModels.ServiceMaterialInput m : materials) {
                    if (m.itemId() != null && m.quantityRequired() > 0) {
                        merged.merge(m.itemId(), m.quantityRequired(), Integer::sum);
                    }
                }
                for (Map.Entry<UUID, Integer> entry : merged.entrySet()) {
                    jdbc.update("""
                            insert into service_materials
                                (id, "organizationId", "projectId", "procedureId", "itemId", "quantityRequired")
                            values
                                (:id, :orgId, :projId, :procId, :itemId, :qty)
                            """,
                            new MapSqlParameterSource()
                                    .addValue("id", UUID.randomUUID())
                                    .addValue("orgId", scope.queryScope().organizationId())
                                    .addValue("projId", scope.projectId())
                                    .addValue("procId", procedureId)
                                    .addValue("itemId", entry.getKey())
                                    .addValue("qty", entry.getValue()));
                }
            }
            return listServiceMaterials(scope, procedureId);
        });
    }

    List<ClinicOperationsModels.InventoryStockWarning> checkProcedureAvailability(
            ClinicScope scope, UUID procedureId, int multiplier) {
        if (procedureId == null) {
            return List.of();
        }
        int mult = Math.max(1, multiplier);
        List<ClinicOperationsModels.ServiceMaterialRow> rows = listServiceMaterials(scope, procedureId);
        List<ClinicOperationsModels.InventoryStockWarning> warnings = new ArrayList<>();
        for (ClinicOperationsModels.ServiceMaterialRow row : rows) {
            int required = row.quantityRequired() * mult;
            if (row.quantityOnHand() < required) {
                int shortfall = required - row.quantityOnHand();
                warnings.add(new ClinicOperationsModels.InventoryStockWarning(
                        row.itemId(),
                        row.itemName(),
                        required,
                        row.quantityOnHand(),
                        shortfall,
                        "Insufficient stock for " + row.itemName() + ": required " + required
                                + " " + row.unitOfMeasure() + ", available " + row.quantityOnHand() + "."));
            }
        }
        return warnings;
    }

    ClinicOperationsModels.DeductionResult deductMaterialsForProcedure(
            ClinicScope scope,
            UUID procedureId,
            int multiplier,
            String referenceType,
            UUID referenceId,
            UUID companionAppointmentId,
            UUID performedByUserId) {
        if (procedureId == null || referenceId == null) {
            return new ClinicOperationsModels.DeductionResult(false, List.of());
        }
        int mult = Math.max(1, multiplier);
        return scope.read(() -> {
            // Idempotency guard: never duplicate deductions on edits or retries, or across linked appointment <-> encounter
            Integer existingCount = jdbc.queryForObject("""
                    select count(*) from inventory_transactions it
                     where %s
                       and it."transactionType" = 'PROCEDURE_DEDUCTION'
                       and (
                            (it."referenceType" = :refType and it."referenceId" = :refId)
                            or (cast(:compApptId as uuid) is not null and it."referenceType" = 'APPOINTMENT' and it."referenceId" = cast(:compApptId as uuid))
                            or (it."referenceType" = 'ENCOUNTER' and it."referenceId" = :refId)
                       )
                    """.formatted(scope.predicate("it")),
                    scope.bind()
                            .addValue("refType", referenceType)
                            .addValue("refId", referenceId)
                            .addValue("compApptId", companionAppointmentId),
                    Integer.class);
            if (existingCount != null && existingCount > 0) {
                return new ClinicOperationsModels.DeductionResult(false, List.of());
            }

            // If this is an appointment completion, also verify no encounter linked to this appointment already deducted
            if ("APPOINTMENT".equals(referenceType)) {
                Integer encDeducted = jdbc.queryForObject("""
                        select count(*) from inventory_transactions it
                          join clinical_encounters ce on ce.id = it."referenceId" and ce."organizationId" = it."organizationId"
                         where %s
                           and it."transactionType" = 'PROCEDURE_DEDUCTION'
                           and it."referenceType" = 'ENCOUNTER'
                           and ce."appointmentId" = :refId
                        """.formatted(scope.predicate("it")),
                        scope.bind().addValue("refId", referenceId),
                        Integer.class);
                if (encDeducted != null && encDeducted > 0) {
                    return new ClinicOperationsModels.DeductionResult(false, List.of());
                }
            }

            List<ClinicOperationsModels.ServiceMaterialRow> materials = listServiceMaterials(scope, procedureId);
            if (materials.isEmpty()) {
                return new ClinicOperationsModels.DeductionResult(false, List.of());
            }

            List<ClinicOperationsModels.InventoryStockWarning> warnings = new ArrayList<>();
            for (ClinicOperationsModels.ServiceMaterialRow mat : materials) {
                int required = mat.quantityRequired() * mult;
                ClinicOperationsModels.InventoryItemRow current = findItemForUpdate(scope, mat.itemId());
                int before = current.quantityOnHand();
                int deductedQty;
                int after;
                int shortfall;
                if (before < required) {
                    after = 0;
                    deductedQty = before;
                    shortfall = required - before;
                    warnings.add(new ClinicOperationsModels.InventoryStockWarning(
                            current.id(),
                            current.name(),
                            required,
                            before,
                            shortfall,
                            "Insufficient stock for " + current.name() + ": required " + required
                                    + " " + current.unitOfMeasure() + ", available " + before
                                    + " (shortfall: " + shortfall + ")."));
                } else {
                    after = before - required;
                    deductedQty = required;
                    shortfall = 0;
                }

                jdbc.update("""
                        update inventory_items ii
                           set "quantityOnHand" = :after,
                               "updatedAt" = (now() at time zone 'utc')
                         where %s and ii.id = :id
                        """.formatted(scope.predicate("ii")),
                        scope.bind().addValue("id", current.id()).addValue("after", after));

                String notes = shortfall > 0
                        ? "Auto-deducted for " + mat.procedureName() + " (shortfall of " + shortfall + " logged)"
                        : "Auto-deducted for " + mat.procedureName();

                insertTransaction(
                        scope,
                        current.clinicId(),
                        current.id(),
                        "PROCEDURE_DEDUCTION",
                        -deductedQty,
                        before,
                        after,
                        shortfall,
                        current.unitCost(),
                        referenceType,
                        referenceId,
                        performedByUserId,
                        notes);
            }
            return new ClinicOperationsModels.DeductionResult(true, warnings);
        });
    }

    UUID resolveProcedureIdForEncounter(
            ClinicScope scope, UUID appointmentId, UUID treatmentPlanItemId, String performedProcedures) {
        return scope.read(() -> {
            if (treatmentPlanItemId != null) {
                List<UUID> ids = jdbc.query("""
                        select tpi."procedureId" from treatment_plan_items tpi
                         where %s and tpi.id = :id and tpi."procedureId" is not null
                         limit 1
                        """.formatted(scope.predicate("tpi")),
                        scope.bind().addValue("id", treatmentPlanItemId),
                        (rs, n) -> rs.getObject("procedureId", UUID.class));
                if (!ids.isEmpty()) {
                    return ids.get(0);
                }
            }
            if (appointmentId != null) {
                List<UUID> ids = jdbc.query("""
                        select a."procedureId" from appointments a
                         where %s and a.id = :id and a."procedureId" is not null
                         limit 1
                        """.formatted(scope.predicate("a")),
                        scope.bind().addValue("id", appointmentId),
                        (rs, n) -> rs.getObject("procedureId", UUID.class));
                if (!ids.isEmpty()) {
                    return ids.get(0);
                }
            }
            if (performedProcedures != null && !performedProcedures.isBlank()) {
                List<UUID> ids = jdbc.query("""
                        select p.id from procedures p
                         where %s and lower(trim(p."name")) = lower(trim(:name))
                         limit 1
                        """.formatted(scope.predicate("p")),
                        scope.bind().addValue("name", performedProcedures),
                        (rs, n) -> rs.getObject("id", UUID.class));
                if (!ids.isEmpty()) {
                    return ids.get(0);
                }
            }
            return null;
        });
    }

    private ClinicOperationsModels.InventoryItemRow findItem(ClinicScope scope, UUID itemId) {
        List<ClinicOperationsModels.InventoryItemRow> rows = jdbc.query("""
                select ii.id, ii."organizationId", ii."projectId", ii."clinicId",
                       coalesce(cl."name", 'General Branch') as "clinicName",
                       ii."name", ii."sku", ii."category", ii."unitOfMeasure",
                       ii."unitCost", ii."quantityOnHand", ii."minStockThreshold",
                       ii."isActive", ii."createdAt", ii."updatedAt"
                  from inventory_items ii
                  left join clinic_locations cl
                         on cl."id" = ii."clinicId"
                        and cl."organizationId" = ii."organizationId"
                 where %s and ii.id = :id
                 limit 1
                """.formatted(scope.predicate("ii")),
                scope.bind().addValue("id", itemId),
                InventoryOperationsStore::mapItem);
        return rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Inventory item not found"));
    }

    private ClinicOperationsModels.InventoryItemRow findItemForUpdate(ClinicScope scope, UUID itemId) {
        List<ClinicOperationsModels.InventoryItemRow> rows = jdbc.query("""
                select ii.id, ii."organizationId", ii."projectId", ii."clinicId",
                       coalesce(cl."name", 'General Branch') as "clinicName",
                       ii."name", ii."sku", ii."category", ii."unitOfMeasure",
                       ii."unitCost", ii."quantityOnHand", ii."minStockThreshold",
                       ii."isActive", ii."createdAt", ii."updatedAt"
                  from inventory_items ii
                  left join clinic_locations cl
                         on cl."id" = ii."clinicId"
                        and cl."organizationId" = ii."organizationId"
                 where %s and ii.id = :id
                 for update of ii
                """.formatted(scope.predicate("ii")),
                scope.bind().addValue("id", itemId),
                InventoryOperationsStore::mapItem);
        return rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Inventory item not found"));
    }

    private UUID insertTransaction(
            ClinicScope scope,
            UUID clinicId,
            UUID itemId,
            String transactionType,
            int quantity,
            int quantityBefore,
            int quantityAfter,
            int shortfallQuantity,
            BigDecimal unitCost,
            String referenceType,
            UUID referenceId,
            UUID performedByUserId,
            String notes) {
        UUID txId = UUID.randomUUID();
        UUID projId = scope.isProjectScoped() ? scope.projectId() : findItemProjectId(scope, itemId);
        jdbc.update("""
                insert into inventory_transactions
                    (id, "organizationId", "projectId", "clinicId", "itemId", "transactionType",
                     "quantity", "quantityBefore", "quantityAfter", "shortfallQuantity", "unitCost",
                     "referenceType", "referenceId", "performedByUserId", "notes")
                values
                    (:id, :orgId, :projId, :clinicId, :itemId, :txType,
                     :qty, :qtyBefore, :qtyAfter, :shortfall, :unitCost,
                     :refType, :refId, :userId, :notes)
                """,
                new MapSqlParameterSource()
                        .addValue("id", txId)
                        .addValue("orgId", scope.queryScope().organizationId())
                        .addValue("projId", projId)
                        .addValue("clinicId", clinicId)
                        .addValue("itemId", itemId)
                        .addValue("txType", transactionType)
                        .addValue("qty", quantity)
                        .addValue("qtyBefore", quantityBefore)
                        .addValue("qtyAfter", quantityAfter)
                        .addValue("shortfall", shortfallQuantity)
                        .addValue("unitCost", unitCost)
                        .addValue("refType", referenceType)
                        .addValue("refId", referenceId)
                        .addValue("userId", performedByUserId)
                        .addValue("notes", notes));
        return txId;
    }

    private UUID findItemProjectId(ClinicScope scope, UUID itemId) {
        return jdbc.queryForObject("""
                select ii."projectId" from inventory_items ii
                 where %s and ii.id = :id limit 1
                """.formatted(scope.predicate("ii")),
                scope.bind().addValue("id", itemId),
                UUID.class);
    }

    private void recordInventoryPurchaseExpense(
            ClinicScope scope, UUID clinicId, String itemName, BigDecimal totalAmount, UUID txId, UUID userId) {
        UUID projId = scope.isProjectScoped() ? scope.projectId() : scope.accessibleProjectIds().get(0);
        jdbc.update("""
                insert into clinic_expenses
                    (id, "organizationId", "projectId", "clinicId", "category", "title", "amount",
                     "currency", "expenseFrequency", "paymentDate", "referenceType", "referenceId",
                     "notes", "createdByUserId")
                values
                    (:id, :orgId, :projId, :clinicId, 'INVENTORY_PURCHASE', :title, :amount,
                     'JOD', 'ONE_TIME', :paymentDate, 'INVENTORY_TRANSACTION', :refId,
                     'Auto-logged from inventory stock purchase', :userId)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("orgId", scope.queryScope().organizationId())
                        .addValue("projId", projId)
                        .addValue("clinicId", clinicId)
                        .addValue("title", "Inventory Purchase: " + itemName)
                        .addValue("amount", scaled(totalAmount))
                        .addValue("paymentDate", java.sql.Date.valueOf(LocalDate.now()))
                        .addValue("refId", txId)
                        .addValue("userId", userId));
    }

    private static ClinicOperationsModels.InventoryItemRow mapItem(ResultSet rs, int n) throws SQLException {
        int qty = rs.getInt("quantityOnHand");
        int threshold = rs.getInt("minStockThreshold");
        return new ClinicOperationsModels.InventoryItemRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("clinicId", UUID.class),
                rs.getString("clinicName"),
                rs.getString("name"),
                rs.getString("sku"),
                rs.getString("category"),
                rs.getString("unitOfMeasure"),
                scaled(rs.getBigDecimal("unitCost")),
                qty,
                threshold,
                qty <= threshold,
                rs.getBoolean("isActive"),
                toLocalDateTime(rs.getTimestamp("createdAt")),
                toLocalDateTime(rs.getTimestamp("updatedAt")));
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value != null ? value.setScale(3, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(3);
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
