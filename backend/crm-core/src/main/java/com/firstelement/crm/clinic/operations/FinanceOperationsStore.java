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
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Persistence & Aggregation for Clinic Operational Expenses, Recurring Templates & P&L Analytics (Req 3). */
final class FinanceOperationsStore {

    private static final Set<String> EXPENSE_CATEGORIES = Set.of(
            "RENT", "ELECTRICITY_WATER", "SALARIES", "MAINTENANCE",
            "INVENTORY_PURCHASE", "LAB_FEES", "OTHER");
    private static final Set<String> TEMPLATE_CATEGORIES = Set.of(
            "RENT", "ELECTRICITY_WATER", "SALARIES", "MAINTENANCE", "OTHER");

    private final NamedParameterJdbcTemplate jdbc;

    FinanceOperationsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ClinicOperationsModels.ExpenseRow> listExpenses(
            ClinicScope scope, UUID clinicId, String category, LocalDate from, LocalDate to) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select e.id, e."organizationId", e."projectId", e."clinicId",
                           coalesce(cl."name", 'General Branch') as "clinicName",
                           e."category", e."title", e."amount", e."currency",
                           e."expenseFrequency", e."paymentDate", e."templateId",
                           e."notes", e."createdAt"
                      from clinic_expenses e
                      left join clinic_locations cl on cl.id = e."clinicId" and cl."organizationId" = e."organizationId"
                     where %s
                    """.formatted(scope.predicate("e")));
            MapSqlParameterSource params = scope.bind();
            if (clinicId != null) {
                sql.append(" and e.\"clinicId\" = :clinicId");
                params.addValue("clinicId", clinicId);
            }
            if (category != null && !category.isBlank()) {
                sql.append(" and e.\"category\" = :category");
                params.addValue("category", category.trim().toUpperCase());
            }
            if (from != null) {
                sql.append(" and e.\"paymentDate\" >= :fromDate");
                params.addValue("fromDate", java.sql.Date.valueOf(from));
            }
            if (to != null) {
                sql.append(" and e.\"paymentDate\" <= :toDate");
                params.addValue("toDate", java.sql.Date.valueOf(to));
            }
            sql.append(" order by e.\"paymentDate\" desc, e.\"createdAt\" desc limit 500");
            return jdbc.query(sql.toString(), params, FinanceOperationsStore::mapExpense);
        });
    }

    ClinicOperationsModels.ExpenseRow createExpense(
            ClinicScope scope, ClinicOperationsModels.CreateExpenseRequest req, UUID createdByUserId) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("An expense must belong to one branch project.");
        }
        String category = (req.category() != null && !req.category().isBlank())
                ? req.category().trim().toUpperCase() : "OTHER";
        if (!EXPENSE_CATEGORIES.contains(category)) {
            throw new ValidationFailedException("Invalid expense category. Allowed: " + EXPENSE_CATEGORIES);
        }
        if (req.title() == null || req.title().isBlank()) {
            throw new ValidationFailedException("title is required.");
        }
        BigDecimal amount = req.amount() != null ? scaled(req.amount()) : BigDecimal.ZERO.setScale(3);
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationFailedException("amount cannot be negative.");
        }
        String freq = (req.expenseFrequency() != null && !req.expenseFrequency().isBlank())
                ? req.expenseFrequency().trim().toUpperCase() : "ONE_TIME";
        if (!"ONE_TIME".equals(freq) && !"RECURRING_MONTHLY".equals(freq)) {
            throw new ValidationFailedException("expenseFrequency must be ONE_TIME or RECURRING_MONTHLY.");
        }
        LocalDate payDate = req.paymentDate() != null ? req.paymentDate() : LocalDate.now();

        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_expenses
                        (id, "organizationId", "projectId", "clinicId", "category", "title",
                         "amount", "currency", "expenseFrequency", "paymentDate", "notes", "createdByUserId")
                    values
                        (:id, :orgId, :projId, :clinicId, :category, :title,
                         :amount, 'JOD', :freq, :paymentDate, :notes, :userId)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("orgId", scope.queryScope().organizationId())
                            .addValue("projId", scope.projectId())
                            .addValue("clinicId", req.clinicId())
                            .addValue("category", category)
                            .addValue("title", req.title().trim())
                            .addValue("amount", amount)
                            .addValue("freq", freq)
                            .addValue("paymentDate", java.sql.Date.valueOf(payDate))
                            .addValue("notes", trimToNull(req.notes()))
                            .addValue("userId", createdByUserId));
            return findExpense(scope, id);
        });
    }

    boolean deleteExpense(ClinicScope scope, UUID expenseId) {
        return scope.read(() -> jdbc.update("""
                delete from clinic_expenses e
                 where %s and e.id = :id
                """.formatted(scope.predicate("e")),
                scope.bind().addValue("id", expenseId)) > 0);
    }

    List<ClinicOperationsModels.RecurringExpenseTemplateRow> listRecurringTemplates(
            ClinicScope scope, UUID clinicId) {
        String currentMonth = YearMonth.now().toString();
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select t.id, t."organizationId", t."projectId", t."clinicId",
                           coalesce(cl."name", 'General Branch') as "clinicName",
                           t."category", t."title", t."defaultAmount", t."dayOfMonth",
                           t."lastConfirmedMonth", t."notes", t."isActive"
                      from clinic_recurring_expense_templates t
                      left join clinic_locations cl on cl.id = t."clinicId" and cl."organizationId" = t."organizationId"
                     where %s
                    """.formatted(scope.predicate("t")));
            MapSqlParameterSource params = scope.bind();
            if (clinicId != null) {
                sql.append(" and t.\"clinicId\" = :clinicId");
                params.addValue("clinicId", clinicId);
            }
            sql.append(" order by t.\"isActive\" desc, t.\"dayOfMonth\" asc, t.\"title\" asc");
            return jdbc.query(sql.toString(), params, (rs, n) -> {
                String lastMonth = rs.getString("lastConfirmedMonth");
                boolean isActive = rs.getBoolean("isActive");
                boolean dueThisMonth = isActive && (lastMonth == null || !currentMonth.equals(lastMonth));
                return new ClinicOperationsModels.RecurringExpenseTemplateRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organizationId", UUID.class),
                        rs.getObject("projectId", UUID.class),
                        rs.getObject("clinicId", UUID.class),
                        rs.getString("clinicName"),
                        rs.getString("category"),
                        rs.getString("title"),
                        scaled(rs.getBigDecimal("defaultAmount")),
                        rs.getInt("dayOfMonth"),
                        lastMonth,
                        dueThisMonth,
                        rs.getString("notes"),
                        isActive);
            });
        });
    }

    ClinicOperationsModels.RecurringExpenseTemplateRow createRecurringTemplate(
            ClinicScope scope, ClinicOperationsModels.CreateRecurringTemplateRequest req) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("Recurring expense template must belong to one branch project.");
        }
        String category = (req.category() != null && !req.category().isBlank())
                ? req.category().trim().toUpperCase() : "RENT";
        if (!TEMPLATE_CATEGORIES.contains(category)) {
            throw new ValidationFailedException("Invalid recurring category. Allowed: " + TEMPLATE_CATEGORIES);
        }
        if (req.title() == null || req.title().isBlank()) {
            throw new ValidationFailedException("title is required.");
        }
        BigDecimal amount = req.defaultAmount() != null ? scaled(req.defaultAmount()) : BigDecimal.ZERO.setScale(3);
        int day = req.dayOfMonth() != null ? Math.min(Math.max(req.dayOfMonth(), 1), 28) : 1;

        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_recurring_expense_templates
                        (id, "organizationId", "projectId", "clinicId", "category", "title",
                         "defaultAmount", "dayOfMonth", "notes", "isActive")
                    values
                        (:id, :orgId, :projId, :clinicId, :category, :title,
                         :amount, :day, :notes, true)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("orgId", scope.queryScope().organizationId())
                            .addValue("projId", scope.projectId())
                            .addValue("clinicId", req.clinicId())
                            .addValue("category", category)
                            .addValue("title", req.title().trim())
                            .addValue("amount", amount)
                            .addValue("day", day)
                            .addValue("notes", trimToNull(req.notes())));
            return findTemplate(scope, id);
        });
    }

    ClinicOperationsModels.ExpenseRow confirmRecurringTemplate(
            ClinicScope scope,
            UUID templateId,
            ClinicOperationsModels.ConfirmRecurringTemplateRequest req,
            UUID userId) {
        return scope.read(() -> {
            ClinicOperationsModels.RecurringExpenseTemplateRow tpl = findTemplate(scope, templateId);
            BigDecimal amount = (req != null && req.amount() != null) ? scaled(req.amount()) : tpl.defaultAmount();
            LocalDate payDate = (req != null && req.paymentDate() != null) ? req.paymentDate() : LocalDate.now();
            String monthKey = (req != null && req.monthKey() != null && !req.monthKey().isBlank())
                    ? req.monthKey().trim()
                    : YearMonth.from(payDate).toString();
            String notes = (req != null && req.notes() != null && !req.notes().isBlank())
                    ? req.notes().trim()
                    : tpl.notes();

            UUID expenseId = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_expenses
                        (id, "organizationId", "projectId", "clinicId", "category", "title",
                         "amount", "currency", "expenseFrequency", "paymentDate", "templateId",
                         "notes", "createdByUserId")
                    values
                        (:id, :orgId, :projId, :clinicId, :category, :title,
                         :amount, 'JOD', 'RECURRING_MONTHLY', :paymentDate, :templateId,
                         :notes, :userId)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", expenseId)
                            .addValue("orgId", tpl.organizationId())
                            .addValue("projId", tpl.projectId())
                            .addValue("clinicId", tpl.clinicId())
                            .addValue("category", tpl.category())
                            .addValue("title", tpl.title() + " (" + monthKey + ")")
                            .addValue("amount", amount)
                            .addValue("paymentDate", java.sql.Date.valueOf(payDate))
                            .addValue("templateId", templateId)
                            .addValue("notes", notes)
                            .addValue("userId", userId));

            jdbc.update("""
                    update clinic_recurring_expense_templates t
                       set "lastConfirmedMonth" = :monthKey, "updatedAt" = (now() at time zone 'utc')
                     where %s and t.id = :id
                    """.formatted(scope.predicate("t")),
                    scope.bind().addValue("id", templateId).addValue("monthKey", monthKey));

            return findExpense(scope, expenseId);
        });
    }

    boolean deleteRecurringTemplate(ClinicScope scope, UUID templateId) {
        return scope.read(() -> jdbc.update("""
                delete from clinic_recurring_expense_templates t
                 where %s and t.id = :id
                """.formatted(scope.predicate("t")),
                scope.bind().addValue("id", templateId)) > 0);
    }

    ClinicOperationsModels.FinancialAnalyticsReport getFinancialAnalytics(
            ClinicScope scope, int year, String periodType, UUID clinicId) {
        int safeYear = (year >= 2000 && year <= 2100) ? year : LocalDate.now().getYear();
        String normalizedPeriod = (periodType != null && !periodType.isBlank())
                ? periodType.trim().toUpperCase() : "QUARTERLY";
        LocalDate yearStart = LocalDate.of(safeYear, 1, 1);
        LocalDate yearEnd = LocalDate.of(safeYear, 12, 31);
        Timestamp tsStart = Timestamp.valueOf(yearStart.atStartOfDay());
        Timestamp tsEnd = Timestamp.valueOf(yearEnd.plusDays(1).atStartOfDay());

        return scope.read(() -> {
            BigDecimal[] revByMonth = new BigDecimal[13];
            BigDecimal[] opExByMonth = new BigDecimal[13];
            BigDecimal[] payoutByMonth = new BigDecimal[13];
            for (int m = 1; m <= 12; m++) {
                revByMonth[m] = BigDecimal.ZERO.setScale(3);
                opExByMonth[m] = BigDecimal.ZERO.setScale(3);
                payoutByMonth[m] = BigDecimal.ZERO.setScale(3);
            }

            // 1a. Invoice payments collected (filtered by project if no clinicId, or across project when clinicId matches)
            if (clinicId == null) {
                jdbc.query("""
                        select extract(month from cp."paymentDate")::int as m,
                               coalesce(sum(cp."amount"), 0.000) as total
                          from clinic_payments cp
                         where %s
                           and cp."paymentDate" >= :tsStart and cp."paymentDate" < :tsEnd
                         group by 1
                        """.formatted(scope.predicate("cp")),
                        scope.bind().addValue("tsStart", tsStart).addValue("tsEnd", tsEnd),
                        rs -> {
                            int m = rs.getInt("m");
                            if (m >= 1 && m <= 12) {
                                revByMonth[m] = revByMonth[m].add(scaled(rs.getBigDecimal("total")));
                            }
                        });
            }

            // 1b. Completed appointments without an invoiced encounter (avoids duplicating invoice payments)
            jdbc.query("""
                    select extract(month from a."appointmentDate")::int as m,
                           coalesce(sum(coalesce(a."price", 0) * coalesce(a."quantity", 1)), 0.000) as total
                      from appointments a
                     where %s
                       and a."status" = 'COMPLETED'
                       and a."appointmentDate" >= :tsStart and a."appointmentDate" < :tsEnd
                       and (cast(:clinicId as uuid) is null or a."clinicId" = cast(:clinicId as uuid))
                       and not exists (
                           select 1 from clinical_encounters ce
                             join clinic_invoices ci on ci."encounterId" = ce.id and ci."organizationId" = ce."organizationId"
                            where ce."appointmentId" = a.id
                              and ce."organizationId" = a."organizationId"
                              and ci."status" != 'CANCELLED'
                       )
                     group by 1
                    """.formatted(scope.predicate("a")),
                    scope.bind()
                            .addValue("tsStart", tsStart)
                            .addValue("tsEnd", tsEnd)
                            .addValue("clinicId", clinicId),
                    rs -> {
                        int m = rs.getInt("m");
                        if (m >= 1 && m <= 12) {
                            revByMonth[m] = revByMonth[m].add(scaled(rs.getBigDecimal("total")));
                        }
                    });

            // 2. Operational Expenses by month and category
            Map<String, BigDecimal> expensesByCategory = new LinkedHashMap<>();
            for (String cat : EXPENSE_CATEGORIES) {
                expensesByCategory.put(cat, BigDecimal.ZERO.setScale(3));
            }
            jdbc.query("""
                    select extract(month from e."paymentDate")::int as m,
                           e."category",
                           coalesce(sum(e."amount"), 0.000) as total
                      from clinic_expenses e
                     where %s
                       and e."paymentDate" >= :dStart and e."paymentDate" <= :dEnd
                       and (cast(:clinicId as uuid) is null or e."clinicId" = cast(:clinicId as uuid))
                     group by 1, 2
                    """.formatted(scope.predicate("e")),
                    scope.bind()
                            .addValue("dStart", java.sql.Date.valueOf(yearStart))
                            .addValue("dEnd", java.sql.Date.valueOf(yearEnd))
                            .addValue("clinicId", clinicId),
                    rs -> {
                        int m = rs.getInt("m");
                        String cat = rs.getString("category");
                        BigDecimal amt = scaled(rs.getBigDecimal("total"));
                        if (m >= 1 && m <= 12) {
                            opExByMonth[m] = opExByMonth[m].add(amt);
                        }
                        expensesByCategory.merge(cat, amt, BigDecimal::add);
                    });

            // 3. Doctor Commission Payouts folded into branch expenses
            jdbc.query("""
                    select extract(month from coalesce(cast(p."paidAt" as date), p."periodEnd"))::int as m,
                           coalesce(sum(p."commissionAmount"), 0.000) as total
                      from clinic_doctor_payouts p
                      left join doctors d on d.id = p."doctorId" and d."organizationId" = p."organizationId"
                     where %s
                       and coalesce(cast(p."paidAt" as date), p."periodEnd") >= :dStart
                       and coalesce(cast(p."paidAt" as date), p."periodEnd") <= :dEnd
                       and (cast(:clinicId as uuid) is null or d."clinicId" = cast(:clinicId as uuid))
                     group by 1
                    """.formatted(scope.predicate("p")),
                    scope.bind()
                            .addValue("dStart", java.sql.Date.valueOf(yearStart))
                            .addValue("dEnd", java.sql.Date.valueOf(yearEnd))
                            .addValue("clinicId", clinicId),
                    rs -> {
                        int m = rs.getInt("m");
                        if (m >= 1 && m <= 12) {
                            payoutByMonth[m] = payoutByMonth[m].add(scaled(rs.getBigDecimal("total")));
                        }
                    });

            BigDecimal totalRev = BigDecimal.ZERO.setScale(3);
            BigDecimal totalOpEx = BigDecimal.ZERO.setScale(3);
            BigDecimal totalPayouts = BigDecimal.ZERO.setScale(3);
            List<ClinicOperationsModels.PeriodFinancialBucket> monthly = new ArrayList<>();
            for (int m = 1; m <= 12; m++) {
                BigDecimal r = revByMonth[m];
                BigDecimal ox = opExByMonth[m];
                BigDecimal dp = payoutByMonth[m];
                BigDecimal totEx = ox.add(dp);
                totalRev = totalRev.add(r);
                totalOpEx = totalOpEx.add(ox);
                totalPayouts = totalPayouts.add(dp);
                String label = Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + safeYear;
                monthly.add(new ClinicOperationsModels.PeriodFinancialBucket(
                        String.format("%04d-%02d", safeYear, m),
                        label,
                        r, ox, dp, totEx, r.subtract(totEx)));
            }

            List<ClinicOperationsModels.PeriodFinancialBucket> quarterly = new ArrayList<>();
            String[] qLabels = {"Q1 (Jan–Mar)", "Q2 (Apr–Jun)", "Q3 (Jul–Sep)", "Q4 (Oct–Dec)"};
            for (int q = 0; q < 4; q++) {
                BigDecimal qr = BigDecimal.ZERO.setScale(3);
                BigDecimal qox = BigDecimal.ZERO.setScale(3);
                BigDecimal qdp = BigDecimal.ZERO.setScale(3);
                for (int m = q * 3 + 1; m <= q * 3 + 3; m++) {
                    qr = qr.add(revByMonth[m]);
                    qox = qox.add(opExByMonth[m]);
                    qdp = qdp.add(payoutByMonth[m]);
                }
                BigDecimal qTotEx = qox.add(qdp);
                quarterly.add(new ClinicOperationsModels.PeriodFinancialBucket(
                        safeYear + "-Q" + (q + 1),
                        qLabels[q],
                        qr, qox, qdp, qTotEx, qr.subtract(qTotEx)));
            }

            // 4. Multi-branch breakdown across clinic locations in scope
            List<ClinicOperationsModels.BranchFinancialBreakdown> branches = jdbc.query("""
                    select cl.id as "clinicId", cl."name" as "branchName",
                           coalesce((select sum(coalesce(a."price", 0) * coalesce(a."quantity", 1))
                                       from appointments a
                                      where a."organizationId" = cl."organizationId"
                                        and a."clinicId" = cl.id
                                        and a."status" = 'COMPLETED'
                                        and a."appointmentDate" >= :tsStart and a."appointmentDate" < :tsEnd), 0.000) as "apptRev",
                           coalesce((select sum(e."amount")
                                       from clinic_expenses e
                                      where e."organizationId" = cl."organizationId"
                                        and e."clinicId" = cl.id
                                        and e."paymentDate" >= :dStart and e."paymentDate" <= :dEnd), 0.000) as "branchExp",
                           coalesce((select sum(p."commissionAmount")
                                       from clinic_doctor_payouts p
                                       join doctors d on d.id = p."doctorId" and d."organizationId" = p."organizationId"
                                      where p."organizationId" = cl."organizationId"
                                        and d."clinicId" = cl.id
                                        and coalesce(cast(p."paidAt" as date), p."periodEnd") >= :dStart
                                        and coalesce(cast(p."paidAt" as date), p."periodEnd") <= :dEnd), 0.000) as "branchPayout"
                      from clinic_locations cl
                     where %s
                       and (cast(:clinicId as uuid) is null or cl.id = cast(:clinicId as uuid))
                     order by cl."name" asc
                    """.formatted(scope.predicate("cl")),
                    scope.bind()
                            .addValue("tsStart", tsStart)
                            .addValue("tsEnd", tsEnd)
                            .addValue("dStart", java.sql.Date.valueOf(yearStart))
                            .addValue("dEnd", java.sql.Date.valueOf(yearEnd))
                            .addValue("clinicId", clinicId),
                    (rs, n) -> {
                        BigDecimal brRev = scaled(rs.getBigDecimal("apptRev"));
                        BigDecimal brExp = scaled(rs.getBigDecimal("branchExp")).add(scaled(rs.getBigDecimal("branchPayout")));
                        return new ClinicOperationsModels.BranchFinancialBreakdown(
                                rs.getObject("clinicId", UUID.class),
                                rs.getString("branchName"),
                                brRev,
                                brExp,
                                brRev.subtract(brExp));
                    });

            BigDecimal totalExpenses = totalOpEx.add(totalPayouts);
            BigDecimal netProfit = totalRev.subtract(totalExpenses);

            return new ClinicOperationsModels.FinancialAnalyticsReport(
                    safeYear,
                    normalizedPeriod,
                    totalRev,
                    totalOpEx,
                    totalPayouts,
                    totalExpenses,
                    netProfit,
                    expensesByCategory,
                    quarterly,
                    monthly,
                    branches);
        });
    }

    private ClinicOperationsModels.ExpenseRow findExpense(ClinicScope scope, UUID id) {
        List<ClinicOperationsModels.ExpenseRow> rows = jdbc.query("""
                select e.id, e."organizationId", e."projectId", e."clinicId",
                       coalesce(cl."name", 'General Branch') as "clinicName",
                       e."category", e."title", e."amount", e."currency",
                       e."expenseFrequency", e."paymentDate", e."templateId",
                       e."notes", e."createdAt"
                  from clinic_expenses e
                  left join clinic_locations cl on cl.id = e."clinicId" and cl."organizationId" = e."organizationId"
                 where %s and e.id = :id
                 limit 1
                """.formatted(scope.predicate("e")),
                scope.bind().addValue("id", id),
                FinanceOperationsStore::mapExpense);
        return rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
    }

    private ClinicOperationsModels.RecurringExpenseTemplateRow findTemplate(ClinicScope scope, UUID id) {
        String currentMonth = YearMonth.now().toString();
        List<ClinicOperationsModels.RecurringExpenseTemplateRow> rows = jdbc.query("""
                select t.id, t."organizationId", t."projectId", t."clinicId",
                       coalesce(cl."name", 'General Branch') as "clinicName",
                       t."category", t."title", t."defaultAmount", t."dayOfMonth",
                       t."lastConfirmedMonth", t."notes", t."isActive"
                  from clinic_recurring_expense_templates t
                  left join clinic_locations cl on cl.id = t."clinicId" and cl."organizationId" = t."organizationId"
                 where %s and t.id = :id
                 limit 1
                """.formatted(scope.predicate("t")),
                scope.bind().addValue("id", id),
                (rs, n) -> {
                    String lastMonth = rs.getString("lastConfirmedMonth");
                    boolean isActive = rs.getBoolean("isActive");
                    boolean dueThisMonth = isActive && (lastMonth == null || !currentMonth.equals(lastMonth));
                    return new ClinicOperationsModels.RecurringExpenseTemplateRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("organizationId", UUID.class),
                            rs.getObject("projectId", UUID.class),
                            rs.getObject("clinicId", UUID.class),
                            rs.getString("clinicName"),
                            rs.getString("category"),
                            rs.getString("title"),
                            scaled(rs.getBigDecimal("defaultAmount")),
                            rs.getInt("dayOfMonth"),
                            lastMonth,
                            dueThisMonth,
                            rs.getString("notes"),
                            isActive);
                });
        return rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Recurring template not found"));
    }

    private static ClinicOperationsModels.ExpenseRow mapExpense(ResultSet rs, int n) throws SQLException {
        java.sql.Date pd = rs.getDate("paymentDate");
        return new ClinicOperationsModels.ExpenseRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("clinicId", UUID.class),
                rs.getString("clinicName"),
                rs.getString("category"),
                rs.getString("title"),
                scaled(rs.getBigDecimal("amount")),
                rs.getString("currency"),
                rs.getString("expenseFrequency"),
                pd != null ? pd.toLocalDate() : null,
                rs.getObject("templateId", UUID.class),
                rs.getString("notes"),
                toLocalDateTime(rs.getTimestamp("createdAt")));
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
