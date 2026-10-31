package com.firstelement.finance.ledger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The default {@link UsageWriteAuditor}, declared in a plain {@code @Configuration} class.
 *
 * <p><b>Why this is not a method on {@code ReportingFinanceModule}.</b> That module class is an
 * {@code @AutoConfiguration}, and the module convention in this codebase is explicitly "no
 * {@code @Bean} here: the services and repositories are annotated and discovered". Adding a bean
 * method to it did not register in the real application context -- the jar shipped the method and
 * {@code app-bootstrap}'s own bean in the same class loaded fine, while this one was never
 * created. A plain {@code @Configuration} class in the scanned package is picked up by the same
 * component scan that already discovers every other finance bean, so the default exists wherever the
 * services do.
 */
@Configuration(proxyBeanMethods = false)
public class UsageWriteAuditorDefault {

    /**
     * The documented default. A deployment that ships Micrometer or an audit stream declares its
     * own {@link UsageWriteAuditor} bean and marks it {@code @Primary}, or excludes this one by
     * name; {@code UsageEventWriter} takes a single auditor, so the seam stays a single bean.
     */
    @Bean
    public UsageWriteAuditor usageWriteAuditor() {
        return new UsageWriteAuditor.Logging();
    }
}