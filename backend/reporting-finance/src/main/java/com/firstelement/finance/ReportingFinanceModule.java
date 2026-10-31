package com.firstelement.finance;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Wires {@code backend/reporting-finance}.
 *
 * <h2>Why an explicit module configuration</h2>
 *
 * <p>Like every other {@code :backend} module: an {@code @AutoConfiguration} plus a
 * {@code @ComponentScan} of its own package, so a test application that scans
 * {@code com.firstelement.finance} alone still gets a wired graph. There is deliberately no
 * {@code @Bean} here — the services and repositories are annotated and discovered, so a second way to
 * construct them would be a second set of wiring that can disagree.
 *
 * <h2>What this module writes, in full</h2>
 *
 * <ol>
 *   <li><b>an append-only row in {@code usage_events}</b>, inside the same transaction as the domain
 *       change that caused the usage;</li>
 *   <li><b>rate cards, versions and lines</b>, plus the one {@code ACTIVE -> RETIRED} transition the
 *       schema's narrow guard accepts;</li>
 *   <li><b>{@code usage_rollup_snapshots}</b>, always as a new generation naming what it supersedes;</li>
 *   <li><b>finance period status transitions</b>, each with a matching
 *       {@code finance_period_lock_events} row in the same transaction;</li>
 *   <li><b>finance adjustments</b> and their approval, rejection and application;</li>
 *   <li><b>reconciliation runs and lines</b>.</li>
 * </ol>
 *
 * <p>That list is the whole write surface, and it is worth reading twice: nothing here updates a
 * computed amount, nothing here writes a revenue that was not derived from a pinned rate, and nothing
 * here touches payment, invoicing or any external billing concern (ADR 0007 rule 7). There is no
 * statement in this module that accepts a table name, a column name or a predicate fragment from a
 * caller, and no route that accepts SQL or a free-form row.
 *
 * <h2>What this module never does</h2>
 *
 * <ul>
 *   <li><b>No DDL.</b> The schema is
 *       {@code V20261005080000__usage_ledger.sql} and
 *       {@code V20261005090000__finance_rate_cards_periods.sql}, and this module is written against
 *       their exact column names so a column that does not exist is a test failure rather than a
 *       production surprise.</li>
 *   <li><b>No purge.</b> ADR 0009 §4 classes these tables as <b>never purge</b> — "finance and audit
 *       retention is governed by its own policy; operational cleanup must never substitute for it".</li>
 *   <li><b>No revenue column, anywhere.</b> Revenue is derived on the rollup snapshot and is checked by
 *       {@code usage_rollup_snapshots_derivation_check}; a stored revenue would give the ledger two
 *       answers for one figure.</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.firstelement.finance")
public class ReportingFinanceModule {

    public static final class Limits {

        private Limits() {
        }

        /** A daily or monthly usage window, in days. Roughly two months of daily buckets. */
        public static final int USAGE_WINDOW_DAYS = 62;

        /** Periods per page. */
        public static final int PERIOD_PAGE = 60;

        /** Period lifecycle events per page. */
        public static final int PERIOD_EVENT_PAGE = 200;

        /** Adjustments per page. */
        public static final int ADJUSTMENT_PAGE = 100;

        /** Reconciliation runs per page. */
        public static final int RECONCILIATION_PAGE = 50;

        /**
         * Usage events behind one drill-through.
         *
         * <p>200 rather than 1000: the events are the expensive part of the response and a reviewer
         * checking whether "the AI tokens look right" needs a screenful, not a dump. The response
         * reports the true count and whether the list was truncated, so nobody mistakes the bound for
         * the whole.
         */
        public static final int DRILL_EVENT_PAGE = 200;

        /** Legacy {@code ai_usage} rows behind a comparison, for drill-through from a variance. */
        public static final int LEGACY_RUN_PAGE = 100;
    }
}
