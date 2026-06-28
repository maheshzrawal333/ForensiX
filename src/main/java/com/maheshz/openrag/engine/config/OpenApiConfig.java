package com.maheshz.openrag.engine.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI forensicEngineOpenAPI() {
        return new OpenAPI()
                .info(new Info().title("OpenRAG Forensics Engine")
                        .description("Enterprise API for asynchronous ingestion and RAG-based analysis of unstructured forensic data.")
                        .version("v1.0.0")
                        .contact(new Contact().name("Mahesh").url("https://github.com/mahesh")))
                // Define the Tenant/Case ID as a global requirement
                .components(new Components()
                        .addSecuritySchemes("Case-Isolation-Header", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Tenant-ID")
                                .description("Enter the active Investigation Case ID (e.g., 'CASE-405') to isolate data access.")))
                .addSecurityItem(new SecurityRequirement().addList("Case-Isolation-Header"));
    }
}
