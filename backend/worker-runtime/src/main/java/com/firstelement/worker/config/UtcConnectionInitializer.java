package com.firstelement.worker.config;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Makes every pooled connection UTC.
 *
 * <h2>Why this is not optional</h2>
 *
 * <p>Phase 5a, risk 5: "The canonical statements use {@code (now() AT TIME ZONE 'UTC')}, and the new
 * defaults write UTC explicitly, but the <em>baseline</em> tables still default to a session-local
 * {@code CURRENT_TIMESTAMP}. Every connection must be UTC. {@code V10 §7} reports
 * {@code SHOW TimeZone} and the application must set it; nothing in the schema can enforce it."
 *
 * <p>The lease comparisons in this module already use {@code (now() AT TIME ZONE 'UTC')}, so they are
 * correct regardless. This closes the other half: a row written by the baseline's
 * {@code DEFAULT CURRENT_TIMESTAMP} on a Riyadh session would store local time, and every later
 * comparison against it would be wrong by three hours. Setting the session zone to UTC is the only
 * fix, and a configuration file is the only place it belongs — so this class exists to apply it to
 * whatever {@code DataSource} the process was given, including one built by a module this phase does
 * not own.
 *
 * <h2>Why a {@code BeanPostProcessor} rather than a property</h2>
 *
 * <p>The API module's {@code application.yml} is not this phase's file. Editing another module's
 * configuration to set a fact every process needs would be the wrong ownership, so the rule travels
 * in code instead. It is applied <em>before</em> the pool initializes, and it is idempotent: a pool
 * that already sets {@code connectionInitSql} is left alone.
 */
@Configuration(proxyBeanMethods = false)
public class UtcConnectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(UtcConnectionInitializer.class);

    public static final String SET_TIME_ZONE_UTC = "SET TIME ZONE 'UTC'";

    @Bean
    static BeanPostProcessor utcSessionTimeZone() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName)
                throws BeansException {
                if (bean instanceof HikariDataSource hikari
                    && (hikari.getConnectionInitSql() == null
                        || hikari.getConnectionInitSql().isBlank())) {
                    hikari.setConnectionInitSql(SET_TIME_ZONE_UTC);
                    log.info("datasource '{}' will open every connection with {}", beanName,
                        SET_TIME_ZONE_UTC);
                }
                return bean;
            }
        };
    }

    /** The SQL every pooled connection runs first. Exposed so a fixture can assert it. */
    public static String utcStatement() {
        return SET_TIME_ZONE_UTC;
    }

    /** Whether this datasource already runs the UTC statement. */
    public static boolean applies(DataSource dataSource) {
        return dataSource instanceof HikariDataSource hikari
            && SET_TIME_ZONE_UTC.equals(hikari.getConnectionInitSql());
    }
}