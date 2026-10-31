package com.firstelement.airag;

import com.firstelement.airag.config.AiRagProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * Wires {@code backend/ai-rag}.
 *
 * <h2>Why an explicit module configuration</h2>
 *
 * <p>Like every other {@code :backend} module: an {@code @AutoConfiguration} plus a {@code @ComponentScan}
 * of its own package, so a test application that scans {@code com.firstelement.airag} alone still gets a
 * wired graph. There is deliberately no {@code @Bean} here — the services and repositories are annotated
 * and discovered, so a second way to construct them would be a second set of wiring that can disagree.
 *
 * <h2>{@code @EnableConfigurationProperties} rather than {@code @ConfigurationPropertiesScan}</h2>
 *
 * <p>Because the deployment's {@code @ConfigurationPropertiesScan} is scoped to
 * {@code com.firstelement.config} ({@code FirstElementProperties}), and this module's properties are
 * {@code firstelement.ai} in its own package. Registering them here means the properties exist in production
 * <em>and</em> in this module's tests, which is the property that stops a test harness from having to
 * supply them.
 *
 * <h2>What this module writes, in full</h2>
 *
 * <ol>
 *   <li>AI runs and their trace: {@code ai_runs}, {@code ai_run_steps}, {@code ai_run_retrievals},
 *       {@code ai_run_tool_calls} — each child row carrying the scope its parent already had;</li>
 *   <li>{@code ai_tool_executions} for <b>every</b> tool request, allowed or refused;</li>
 *   <li>{@code ai_approvals} and {@code ai_approval_decisions} — the human approval record;</li>
 *   <li>{@code ai_redaction_records} — counts, never values;</li>
 *   <li>{@code ai_provenance_records} — one row per run, naming agent version, prompt version, model,
 *       retrievals, tool counts, tokens, cost, redactions and disposition;</li>
 *   <li>{@code ai_budget_consumptions} and the legacy {@code ai_usage} comparison row;</li>
 *   <li>{@code usage_events} through the finance module's single writer — {@code AI_REQUEST} and
 *       {@code AI_TOKEN} per run;</li>
 *   <li>knowledge state: {@code documents."status"} transitions and {@code document_chunks} rows, plus
 *       deletion propagation that removes both.</li>
 * </ol>
 *
 * <p>That list is the whole write surface, and it is worth reading twice: nothing here writes a customer
 * message, nothing here publishes an outbox intent, and nothing here updates a computed amount. There is no
 * statement in this module that accepts a table name, a column name, a SQL fragment or a column list from a
 * caller.
 *
 * <h2>What this module never does</h2>
 *
 * <ul>
 *   <li><b>No DDL.</b> The schema is {@code V20261005100000}, {@code …5110000} and {@code …5120000}, and
 *       this module is written against their exact column names so a column that does not exist is a test
 *       failure rather than a production surprise. {@link
 *       com.firstelement.airag.backfill.AiRunChildScopeBackfill} writes data and has no DDL at all.</li>
 *   <li><b>No send.</b> {@link com.firstelement.airag.send.AiSendAuthorization} decides; Phase 6's
 *       {@code AiSendService} sends, under the conversation lock.</li>
 *   <li><b>No chain-of-thought, ever.</b> {@code ai_run_steps."detail"} is built from a declared record, so
 *       it cannot carry a prompt, a model answer or a reasoning trace.</li>
 *   <li><b>No credential, ever.</b> {@link com.firstelement.airag.config.AiRagProperties} has no key
 *       property, {@code ai_model_registry} has no credential column, and every string that could carry one
 *       passes through {@link com.firstelement.airag.provider.SecretScrubber} on its way to a log or a row.</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.firstelement.airag")
@EnableConfigurationProperties(AiRagProperties.class)
public class AiRagModule {

    public static final class Limits {

        private Limits() {
        }

        /**
         * Retrieved chunks per run.
         *
         * <p>Bounded because every chunk is customer content that enters a prompt, and the budget gate's
         * estimate is computed on exactly what the prompt contains. An unbounded retrieval would make the
         * token ceiling advisory.
         */
        public static final int DEFAULT_RETRIEVAL_LIMIT = 5;
    }
}