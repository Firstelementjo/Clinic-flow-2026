package com.firstelement.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

/**
 * The contract is served from the operator management port and stays behind the same
 * authenticated security chain as the API: an unauthenticated caller gets 401, and an
 * authenticated operator gets the spec.
 */
@Configuration
@OpenAPIDefinition(info = @Info(
    title = "FirstElement API",
    version = "v1",
    description = "FirstElement CRM & Helpdesk API. Errors follow the stable /api/v1 error contract."
))
@SecurityScheme(
    name = "sessionCookie",
    type = SecuritySchemeType.APIKEY,
    in = io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.COOKIE,
    paramName = "fe_session",
    description = "Server-side browser session cookie. Mutations also require the X-XSRF-TOKEN header."
)
public class OpenApiConfig {}