package com.faber.api.im.core.biz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.mapper.ImMessageMapper;
import com.faber.api.im.core.vo.req.ImMessageContextReqVo;
import com.faber.core.config.mybatis.interceptor.FaTenantInterceptor;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.constant.FaSetting;
import jakarta.validation.Validation;
import com.faber.core.context.TenantContext;
import com.faber.core.exception.BuzzException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImMessageContextTest {
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
    void invalidIdsStopBeforePermissionOrMessageQueries() {
        assertThrows(BuzzException.class, () -> biz.context(null));
        assertThrows(BuzzException.class, () -> biz.pageAfter(null));
        for (Long invalid : new Long[]{null, 0L, -1L}) {
            assertThrows(BuzzException.class, () -> biz.context(query(invalid, 100L)));
            assertThrows(BuzzException.class, () -> biz.pageAfter(query(42L, invalid)));
        }
        verifyNoInteractions(participants, mapper);
    }

    @Test
    void beanValidationRejectsMissingAndNonPositiveIds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(query(42L, 100L)).isEmpty());
            for (Long invalid : new Long[]{null, 0L, -1L}) {
                assertFalse(validator.validate(query(invalid, 100L)).isEmpty());
                assertFalse(validator.validate(query(42L, invalid)).isEmpty());
            }
        }
    }

    @Test
    void missingTenantCannotReadContextOrPageAfter() {
        var actualParticipants = new ImParticipantBiz();
        var settings = mock(FaSetting.class);
        when(settings.isTenantEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(actualParticipants, "faSetting", settings);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", actualParticipants);
        assertThrows(BuzzException.class, () -> biz.context(query(42L, 100L)));
        assertThrows(BuzzException.class, () -> biz.pageAfter(query(42L, 100L)));
        verifyNoInteractions(mapper);
    }

    @Test
    void inaccessibleConversationCannotReadContextOrNewerMessages() {
        doThrow(new BuzzException("无权访问该会话")).when(participants).requireParticipant(42L, "member");
        assertThrows(BuzzException.class, () -> biz.context(query(42L, 100L)));
        assertThrows(BuzzException.class, () -> biz.pageAfter(query(42L, 100L)));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingOrWithdrawnTargetCannotReturnContext() {
        assertThrows(BuzzException.class, () -> biz.context(query(42L, 100L)));
        var withdrawn = message(100);
        withdrawn.setIsWithdrawn(true);
        when(mapper.contextTarget(42L, 100L)).thenReturn(withdrawn);
        assertThrows(BuzzException.class, () -> biz.context(query(42L, 100L)));
        verify(mapper, never()).contextBefore(anyLong(), anyLong());
        verify(mapper, never()).contextAfter(anyLong(), anyLong());
    }

    @Test
    void contextKeepsNearestTwentyOnEachSideAscendingAndDoesNotWriteReadState() {
        var target = message(100);
        when(mapper.contextTarget(42L, 100L)).thenReturn(target);
        when(mapper.contextBefore(42L, 100L)).thenReturn(LongStream.rangeClosed(79, 99)
                .mapToObj(i -> message(178 - i)).toList());
        when(mapper.contextAfter(42L, 100L)).thenReturn(messages(101, 121));
        var result = biz.context(query(42L, 100L));
        assertEquals(LongStream.rangeClosed(80, 120).boxed().toList(), ids(result.getRows()));
        assertTrue(result.isHasOlder());
        assertTrue(result.isHasNewer());
        assertSame(target, result.getRows().get(20));
        var order = inOrder(participants, mapper);
        order.verify(participants).requireParticipant(42L, "member");
        order.verify(mapper).contextTarget(42L, 100L);
        order.verify(mapper).contextBefore(42L, 100L);
        order.verify(mapper).contextAfter(42L, 100L);
        verifyNoMoreInteractions(participants, mapper);
    }

    @Test
    void emptySidesReturnOnlyTargetWithoutContinuation() {
        when(mapper.contextTarget(42L, 100L)).thenReturn(message(100));
        when(mapper.contextBefore(42L, 100L)).thenReturn(List.of());
        when(mapper.contextAfter(42L, 100L)).thenReturn(List.of());
        var result = biz.context(query(42L, 100L));
        assertEquals(List.of(100L), ids(result.getRows()));
        assertFalse(result.isHasOlder());
        assertFalse(result.isHasNewer());
    }

    @Test
    void exactlyTwentyIsLastPageAndPageAfterDoesNotRequireCursorToExist() {
        when(mapper.contextAfter(42L, 100L)).thenReturn(messages(101, 120));
        var last = biz.pageAfter(query(42L, 100L));
        assertEquals(LongStream.rangeClosed(101, 120).boxed().toList(), ids(last.getRows()));
        assertFalse(last.isHasNewer());
        when(mapper.contextAfter(42L, 100L)).thenReturn(messages(101, 121));
        var more = biz.pageAfter(query(42L, 100L));
        assertEquals(20, more.getRows().size());
        assertTrue(more.isHasNewer());
        verify(participants, times(2)).requireParticipant(42L, "member");
        verify(mapper, times(2)).contextAfter(42L, 100L);
        verifyNoMoreInteractions(participants, mapper);
    }

    @Test
    void sqlQueriesAreBoundTenantScopedIncludeAvatarsAndUsePortableFixedLimit() throws Exception {
        var configuration = new MybatisConfiguration();
        String resource = "mapper/im/core/ImMessageMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        TenantContext.setTenantId("tenant-a");
        for (String method : List.of("contextTarget", "contextBefore", "contextAfter")) {
            var statement = configuration.getMappedStatement("com.faber.api.im.core.mapper.ImMessageMapper." + method);
            var bound = statement.getBoundSql(Map.of("conversationId", 42L, "messageId", 100L));
            String sql = new TestableTenantInterceptor().rewrite(bound.getSql()).toLowerCase(Locale.ROOT);
            assertTrue(sql.contains("t.tenant_id = 'tenant-a'"), sql);
            assertTrue(sql.contains("t.conversation_id = ?"), sql);
            assertTrue(sql.contains("t.deleted = false"), sql);
            assertTrue(sql.contains("u.img as sender_user_img"), sql);
            assertFalse(sql.contains("t.type ="), sql);
            assertEquals(List.of("conversationId", "messageId"),
                    bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
            if (!method.equals("contextTarget")) {
                assertTrue(sql.contains("limit 21"), sql);
                assertTrue(sql.contains(method.equals("contextBefore") ? "t.id < ?" : "t.id > ?"), sql);
                assertTrue(sql.contains(method.equals("contextBefore") ? "order by t.id desc" : "order by t.id asc"), sql);
            } else {
                assertTrue(sql.contains("t.id = ?"), sql);
            }
            CCJSqlParserUtil.parse(sql);
        }
    }

    private ImMessageContextReqVo query(Long conversationId, Long messageId) {
        var query = new ImMessageContextReqVo();
        query.setConversationId(conversationId);
        query.setMessageId(messageId);
        return query;
    }

    private ImMessage message(long id) {
        var message = new ImMessage();
        message.setId(id);
        return message;
    }

    private List<ImMessage> messages(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(this::message).toList();
    }

    private List<Long> ids(List<ImMessage> messages) {
        return messages.stream().map(ImMessage::getId).toList();
    }

    private static class TestableTenantInterceptor extends FaTenantInterceptor {
        String rewrite(String sql) throws Exception {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            processSelect(select, 0, sql, null);
            return select.toString();
        }
    }
}
