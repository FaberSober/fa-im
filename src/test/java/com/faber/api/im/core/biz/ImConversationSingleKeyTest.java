package com.faber.api.im.core.biz;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImConversationSingleKeyTest {

    @Test
    void shouldNormalizeSingleChatUserOrder() {
        List<String> leftToRight = ImConversationBiz.normalizeSingleUserIds("user-z", "user-a");
        List<String> rightToLeft = ImConversationBiz.normalizeSingleUserIds("user-a", "user-z");

        assertEquals(List.of("user-a", "user-z"), leftToRight);
        assertEquals(leftToRight, rightToLeft);
        assertEquals("user-a,user-z", String.join(",", leftToRight));
    }

    @Test
    void shouldCreateUniqueSingleKeyIndexForMySqlAndPostgreSql() throws IOException {
        assertUniqueSingleKeyIndex("mysql");
        assertUniqueSingleKeyIndex("postgre");
    }

    private void assertUniqueSingleKeyIndex(String databaseType) throws IOException {
        ClassPathResource resource = new ClassPathResource(
                "sql/fa-im/" + databaseType + "/1.0.2_im_ddl.sql"
        );
        String sql;
        try (var input = resource.getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String normalizedSql = sql.replace('`', '"')
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertTrue(normalizedSql.contains(
                "create unique index \"uk_im_conversation_single_key\" "
                        + "on \"im_conversation\" (\"single_key\")"
        ), "单聊双方标识必须有唯一索引: " + databaseType);
    }

}
