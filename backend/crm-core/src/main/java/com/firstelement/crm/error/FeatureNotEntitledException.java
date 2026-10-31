package com.firstelement.crm.error;

import org.springframework.http.HttpStatus;
import java.util.Map;

/**
 * Thrown when a clinic attempts to access or execute an operation for a feature
 * that is not included in its subscription plan and has no active override
 * (e.g., CLINIC_AI, CHANNEL_WHATSAPP, CHANNEL_TELEGRAM, CHANNEL_INSTAGRAM).
 */
public class FeatureNotEntitledException extends CrmDomainException {

    private static final long serialVersionUID = 1L;

    public FeatureNotEntitledException(String featureKey) {
        super(
            HttpStatus.FORBIDDEN,
            "FEATURE_NOT_ENTITLED",
            "Feature '" + featureKey + "' is not included in your current subscription plan. Please contact your administrator to upgrade.",
            Map.of("featureKey", featureKey),
            false
        );
    }
}
