package com.aideep.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {
    private static final String USER_API_PREFIX = "/v1/api/aideep";
    private static final String AUTH_API_PREFIX = "/v1/aideep/api";

    @Bean
    public OpenAPI aideepOpenAPI() {
        return new OpenAPI().info(new Info()
                        .title("aideep API")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(
                        new SecurityRequirement().addList("bearerAuth"));
    }

    /**
     * 문서의 공통 접두어만 서버 주소로 옮긴다. Auth API는 기존 주소가 달라 경로별 서버로 재정의한다.
     */
    @Bean
    public OpenApiCustomizer simplifyApiPaths() {
        return openAPI -> {
            if (openAPI.getPaths() == null || openAPI.getPaths().keySet().stream()
                    .noneMatch(path -> path.startsWith(USER_API_PREFIX + "/")
                            || path.startsWith(AUTH_API_PREFIX + "/"))) {
                return;
            }
            List<Server> servers = openAPI.getServers() == null || openAPI.getServers().isEmpty()
                    ? List.of(new Server().url("/")) : openAPI.getServers();
            Paths paths = new Paths();
            paths.setExtensions(openAPI.getPaths().getExtensions());
            openAPI.getPaths().forEach((path, pathItem) -> {
                String prefix = path.startsWith(USER_API_PREFIX + "/") ? USER_API_PREFIX
                        : path.startsWith(AUTH_API_PREFIX + "/") ? AUTH_API_PREFIX : "";
                String displayPath = path.substring(prefix.length());
                if (paths.containsKey(displayPath)) {
                    throw new IllegalStateException("Duplicate Swagger path: " + displayPath);
                }
                if (!prefix.isEmpty()) {
                    pathItem.setServers(serversWithPrefix(servers, prefix));
                }
                paths.addPathItem(displayPath, pathItem);
            });
            openAPI.setPaths(paths);
            openAPI.setServers(serversWithPrefix(servers, USER_API_PREFIX));
        };
    }

    private List<Server> serversWithPrefix(List<Server> servers, String prefix) {
        if (prefix.isEmpty()) {
            return servers;
        }
        return servers.stream().map(server -> new Server()
                .url(server.getUrl().replaceAll("/+$", "") + prefix)
                .description(server.getDescription())
                .variables(server.getVariables())
                .extensions(server.getExtensions())).toList();
    }

    /**
     * 컨트롤러 시그니처는 순수 DTO지만 실제 응답은 ResponseWrappingAdvice가 감싸므로, 문서가 실제 응답과 어긋나지 않도록 2xx 응답 스키마를 공통 포맷으로 치환한다.
     */
    @Bean
    public OperationCustomizer wrapSuccessResponseSchema() {
        return (operation, handlerMethod) -> {
            if (operation.getResponses() == null) {
                return operation;
            }
            operation.getResponses().forEach((statusCode, apiResponse) -> {
                if (!statusCode.startsWith("2") || apiResponse.getContent() == null) {
                    return;
                }
                apiResponse.getContent().values().forEach(mediaType -> {
                    Schema<?> original = mediaType.getSchema();
                    if (original != null && !isAlreadyWrapped(original)) {
                        mediaType.setSchema(wrap(original));
                    }
                });
            });
            return operation;
        };
    }

    private boolean isAlreadyWrapped(Schema<?> schema) {
        String ref = schema.get$ref();
        if (ref != null && ref.contains("ResponseHandler")) {
            return true;
        }
        return schema.getProperties() != null && schema.getProperties().containsKey("resultType");
    }

    private Schema<Object> wrap(Schema<?> successSchema) {
        return new ObjectSchema()
                .addProperty("resultType", new StringSchema()._enum(List.of("SUCCESS")))
                .addProperty("error", new ObjectSchema().nullable(true))
                .addProperty("success", successSchema);
    }
}
