package com.aideep.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;

/**
 * 컨트롤러 시그니처(DTO)와 실제 응답(래핑됨)이 다르므로, 문서 스키마도 공통 포맷으로 치환되는지 검증한다.
 */
class SwaggerConfigTest {

    private final OperationCustomizer operationCustomizer = new SwaggerConfig().wrapSuccessResponseSchema();

    @Test
    void preservesDeploymentPrefixAndUnrelatedPaths() {
        PathItem userPathItem = new PathItem().get(new Operation().operationId("user"));
        PathItem authPathItem = new PathItem().post(new Operation().operationId("login"));
        PathItem healthPathItem = new PathItem().get(new Operation().operationId("health"));
        OpenAPI openAPI = new OpenAPI().addServersItem(new Server().url("https://api.example.com/gateway/"))
                .paths(new Paths().addPathItem("/v1/api/aideep/user/", userPathItem)
                        .addPathItem("/v1/aideep/api/auth/login", authPathItem)
                        .addPathItem("/health", healthPathItem));

        new SwaggerConfig().simplifyApiPaths().customise(openAPI);

        assertThat(openAPI.getServers().getFirst().getUrl()).isEqualTo("https://api.example.com/gateway/v1/api/aideep");
        assertThat(openAPI.getPaths()).containsOnlyKeys("/user/", "/auth/login", "/health");
        assertThat(openAPI.getPaths().get("/user/")).isSameAs(userPathItem);
        assertThat(authPathItem.getServers().getFirst().getUrl()).isEqualTo(
                "https://api.example.com/gateway/v1/aideep/api");
        assertThat(healthPathItem.getServers()).isNull();
    }

    @Test
    @DisplayName("2xx 응답 스키마를 공통 포맷으로 감싸고 원본은 success 아래로 옮긴다")
    void wraps2xxSchema() {
        Operation operation = operationWith("200", new Schema<>().$ref("#/components/schemas/UserResponse"));

        operationCustomizer.customize(operation, null);

        Schema<?> wrapped = schemaOf(operation, "200");
        assertThat(wrapped.getProperties()).containsOnlyKeys("resultType", "error", "success");
        assertThat(wrapped.getProperties().get("success").get$ref()).isEqualTo("#/components/schemas/UserResponse");
    }

    @Test
    @DisplayName("이미 ResponseHandler를 참조하는 스키마는 다시 감싸지 않는다")
    void doesNotDoubleWrap() {
        Schema<?> original = new Schema<>().$ref("#/components/schemas/ResponseHandlerUserResponse");
        Operation operation = operationWith("200", original);

        operationCustomizer.customize(operation, null);

        assertThat(schemaOf(operation, "200")).isSameAs(original);
    }

    @Test
    @DisplayName("4xx 응답 스키마는 건드리지 않는다")
    void leavesErrorResponseUntouched() {
        Schema<?> original = new Schema<>().$ref("#/components/schemas/ErrorBody");
        Operation operation = operationWith("400", original);

        operationCustomizer.customize(operation, null);

        assertThat(schemaOf(operation, "400")).isSameAs(original);
    }

    private Operation operationWith(String statusCode, Schema<?> schema) {
        Content content = new Content().addMediaType("application/json", new MediaType().schema(schema));
        return new Operation().responses(new ApiResponses()
                .addApiResponse(statusCode, new ApiResponse().content(content)));
    }

    private Schema<?> schemaOf(Operation operation, String statusCode) {
        return operation.getResponses().get(statusCode).getContent().get("application/json").getSchema();
    }
}
