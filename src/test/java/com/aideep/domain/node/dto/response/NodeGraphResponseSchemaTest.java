package com.aideep.domain.node.dto.response;

import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * springdoc은 Jackson 2 introspection으로 응답 타입을 읽는다. 응답 DTO가 Jackson 3 전용 타입(예: {@code ObjectNode})을 그대로
 * 노출하면 {@code /v3/api-docs} 생성이 통째로 실패하므로, 스키마가 해석되는지 검증한다.
 */
class NodeGraphResponseSchemaTest {

    @Test
    void 응답_스키마가_해석된다() {
        assertThatCode(() -> ModelConverters.getInstance().readAllAsResolvedSchema(NodeGraphResponse.class))
                .doesNotThrowAnyException();
    }

    @Test
    void 노드_data는_자유형식_객체로_문서화된다() {
        var resolved = ModelConverters.getInstance().readAllAsResolvedSchema(NodeGraphResponse.class);

        var nodeResponseSchema = resolved.referencedSchemas.get("NodeResponse");
        assertThat(nodeResponseSchema.getProperties()).containsKey("data");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) nodeResponseSchema.getProperties().get("data"))
                .getType()).isEqualTo("object");
    }
}
