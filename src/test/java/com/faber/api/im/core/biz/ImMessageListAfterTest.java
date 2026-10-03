package com.faber.api.im.core.biz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.mapper.ImMessageMapper;
import com.faber.api.im.core.vo.req.ImMessageListAfterReqVo;
import com.faber.core.config.mybatis.interceptor.FaTenantInterceptor;
import com.faber.core.constant.FaSetting;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.context.TenantContext;
import com.faber.core.exception.BuzzException;
import jakarta.validation.Validation;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImMessageListAfterTest {

    private final ImParticipantBiz participants = mock(ImParticipantBiz.class);
    private final ImMessageMapper mapper = mock(ImMessageMapper.class);
    private final ImMessageBiz biz = new ImMessageBiz();

    @BeforeEach
    void setUp() {
        BaseContextHandler.setUserId("member");
        ReflectionTestUtils.setField(biz, "imParticipantBiz", participants);
        ReflectionTestUtils.setField(biz, "baseMapper", mapper);
    }

    @AfterEach
    void clearContext() {
        BaseContextHandler.remove();
    }

    @Test
    void rejectsMissingOrInvalidIdsBeforeAccessingMessages() {
        assertThrows(BuzzException.class, () -> biz.listAfter(null));
        for (ImMessageListAfterReqVo query : List.of(query(null, 1L), query(0L, 1L),
                query(-1L, 1L), query(42L, null), query(42L, -1L))) {
            assertThrows(BuzzException.class, () -> biz.listAfter(query));
        }
        verifyNoInteractions(participants, mapper);
    }

    @Test
    void beanValidationRequiresIdsButAcceptsEmptyConversationCursor() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(query(42L, 0L)).isEmpty());
            assertFalse(validator.validate(query(null, 0L)).isEmpty());
            assertFalse(validator.validate(query(42L, null)).isEmpty());
            assertFalse(validator.validate(query(0L, 1L)).isEmpty());
            assertFalse(validator.validate(query(42L, -1L)).isEmpty());
        }
    }

    @Test
    void rejectsNonMemberBeforeReadingMessages() {
        doThrow(new BuzzException("无权访问该会话"))
                .when(participants).requireParticipant(42L, "member");
        assertThrows(BuzzException.class, () -> biz.listAfter(query(42L, 1L)));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsMissingTenantBeforeReadingMessages() {
        ImParticipantBiz tenantParticipants = new ImParticipantBiz();
        FaSetting settings = mock(FaSetting.class);
        when(settings.isTenantEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(tenantParticipants, "faSetting", settings);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", tenantParticipants);
        assertThrows(BuzzException.class, () -> biz.listAfter(query(42L, 1L)));
        verifyNoInteractions(mapper);
    }

    @Test
    void returnsAuthorizedRowsWithoutMutatingReadOrSendState() {
        ImMessage message = new ImMessage();
        message.setId(99L);
        message.setSenderUserImg("avatar.png");
        message.setClientMessageId("mobile_retry_1");
        List<ImMessage> rows = List.of(message);
        ImMessageListAfterReqVo query = query(42L, 0L);
        when(mapper.listAfter(query)).thenReturn(rows);
        assertSame(rows, biz.listAfter(query));
        InOrder order = inOrder(participants, mapper);
        order.verify(participants).requireParticipant(42L, "member");
        order.verify(mapper).listAfter(query);
        verifyNoMoreInteractions(participants, mapper);
    }

    @Test
    void sqlUsesTenantScopeAscendingIdCursorAndFixedLimit() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        String resource = "mapper/im/core/ImMessageMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var boundSql = configuration.getMappedStatement("com.faber.api.im.core.mapper.ImMessageMapper.listAfter")
                .getBoundSql(Map.of("query", query(42L, 100L)));
        TenantContext.setTenantId("tenant-a");
        String sql = new TestableTenantInterceptor().rewrite(boundSql.getSql()).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("t.tenant_id = 'tenant-a'"), sql);
        assertTrue(sql.contains("t.deleted = false"), sql);
        assertTrue(sql.contains("t.conversation_id = ?"), sql);
        assertTrue(sql.contains("t.id > ?"), sql);
        assertTrue(sql.contains("order by t.id asc limit 100"), sql);
        assertFalse(sql.contains("offset"), sql);
        assertTrue(sql.contains("t.*") && sql.contains("u.img as sender_user_img"), sql);
        assertEquals(List.of("query.conversationId", "query.afterMessageId"),
                boundSql.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    private ImMessageListAfterReqVo query(Long conversationId, Long afterMessageId) {
        ImMessageListAfterReqVo query = new ImMessageListAfterReqVo();
        query.setConversationId(conversationId);
        query.setAfterMessageId(afterMessageId);
        return query;
    }

    private static class TestableTenantInterceptor extends FaTenantInterceptor {
        String rewrite(String sql) throws Exception {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            processSelect(select, 0, sql, null);
            return select.toString();
        }
    }
}
