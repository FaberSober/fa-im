package com.faber.api.im.core.biz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.faber.api.im.core.mapper.ImMessageMapper;
import com.faber.api.im.core.vo.req.ImMessageSearchTextReqVo;
import com.faber.api.im.core.vo.ret.ImMessageSearchRetVo;
import com.faber.core.config.mybatis.interceptor.FaTenantInterceptor;
import com.faber.core.constant.FaSetting;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.context.TenantContext;
import com.faber.core.exception.BuzzException;
import com.faber.core.vo.query.BasePageQuery;
import com.github.pagehelper.PageHelper;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImMessageSearchTextTest {
    private final ImParticipantBiz participants = mock(ImParticipantBiz.class);
    private final ImMessageMapper mapper = mock(ImMessageMapper.class);
    private final ImMessageBiz biz = new ImMessageBiz();

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        BaseContextHandler.setUserId("member");
        ReflectionTestUtils.setField(biz, "imParticipantBiz", participants);
        ReflectionTestUtils.setField(biz, "baseMapper", mapper);
    }

    @AfterEach
    void clearContext() {
        BaseContextHandler.remove();
        TenantContext.clear();
        PageHelper.clearPage();
    }

    @Test
    void invalidRequestCannotAccessMessages() {
        assertThrows(BuzzException.class, () -> biz.searchText(null));
        assertThrows(BuzzException.class, () -> biz.searchText(new BasePageQuery<>()));
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertThrows(BuzzException.class, () -> biz.searchText(query(id, "hello")));
        }
        for (String keyword : new String[]{null, "", "   ", "a".repeat(101)}) {
            assertThrows(BuzzException.class, () -> biz.searchText(query(42L, keyword)));
        }
        for (int size : new int[]{0, -1, 101}) {
            var query = query(42L, "hello");
            query.setPageSize(size);
            assertThrows(BuzzException.class, () -> biz.searchText(query));
        }
        var query = query(42L, "hello");
        query.setCurrent(0);
        assertThrows(BuzzException.class, () -> biz.searchText(query));
        verifyNoInteractions(participants, mapper);
    }

    @Test
    void deniedMembershipStopsBeforeSearch() {
        doThrow(new BuzzException("无权访问该会话")).when(participants).requireParticipant(42L, "member");
        assertThrows(BuzzException.class, () -> biz.searchText(query(42L, "hello")));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingTenantStopsBeforeSearch() {
        var actualParticipants = new ImParticipantBiz();
        var settings = mock(FaSetting.class);
        when(settings.isTenantEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(actualParticipants, "faSetting", settings);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", actualParticipants);
        assertThrows(BuzzException.class, () -> biz.searchText(query(42L, "hello")));
        verifyNoInteractions(mapper);
    }

    @Test
    void trimsAndEscapesLiteralKeywordAndUsesRequestedPageWithoutUpdatingReadState() {
        var query = query(42L, "  A!%_B  ");
        query.setCurrent(3);
        query.setPageSize(20);
        var message = new ImMessageSearchRetVo();
        message.setId(99L);
        message.setSenderName("张三");
        when(mapper.searchText(42L, "A!!!%!_B", null, null, null)).thenAnswer(invocation -> {
            var page = PageHelper.<ImMessageSearchRetVo>getLocalPage();
            assertEquals(3, page.getPageNum());
            assertEquals(20, page.getPageSize());
            page.setTotal(45);
            page.add(message);
            return page;
        });
        var result = biz.searchText(query);
        assertEquals(List.of(message), result.getData().getRows());
        assertEquals(45, result.getData().getTotal());
        assertEquals(3, result.getData().getPagination().getCurrent());
        var order = inOrder(participants, mapper);
        order.verify(participants).requireParticipant(42L, "member");
        order.verify(mapper).searchText(42L, "A!!!%!_B", null, null, null);
        verifyNoMoreInteractions(participants, mapper);
    }

    @Test
    void acceptsHundredCharacterKeywordAfterTrim() {
        when(mapper.searchText(42L, "a".repeat(100), null, null, null)).thenReturn(List.of());
        assertDoesNotThrow(() -> biz.searchText(query(42L, "  " + "a".repeat(100) + "  ")));
        verify(mapper).searchText(42L, "a".repeat(100), null, null, null);
    }

    @Test
    void sqlIsBoundScopedAndExcludesNonTextDeletedWithdrawnMessages() throws Exception {
        var configuration = new MybatisConfiguration();
        String resource = "mapper/im/core/ImMessageMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement("com.faber.api.im.core.mapper.ImMessageMapper.searchText");
        var bound = statement.getBoundSql(Map.of("conversationId", 42L, "escapedKeyword", "'!!!%!_"));
        TenantContext.setTenantId("tenant-a");
        String sql = new TestableTenantInterceptor().rewrite(bound.getSql()).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("t.tenant_id = 'tenant-a'"), sql);
        assertTrue(sql.contains("t.conversation_id = ?"), sql);
        assertTrue(sql.contains("t.deleted = false"), sql);
        assertTrue(sql.contains("t.type = 1"), sql);
        assertTrue(sql.contains("t.is_withdrawn = false or t.is_withdrawn is null"), sql);
        assertTrue(sql.contains("lower(t.content) like lower(concat('%', ?, '%')) escape '!'"), sql);
        assertTrue(sql.contains("order by t.id desc"), sql);
        assertTrue(sql.contains("u.name as sender_name"), sql);
        assertFalse(sql.contains("'!!!%!_"), sql);
        assertEquals(List.of("conversationId", "escapedKeyword"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        var page = new com.github.pagehelper.Page<ImMessageSearchRetVo>(2, 20);
        String mysql = new com.github.pagehelper.dialect.helper.MySqlDialect()
                .getPageSql(sql, page, new org.apache.ibatis.cache.CacheKey()).toLowerCase(Locale.ROOT);
        String postgre = new com.github.pagehelper.dialect.helper.PostgreSqlDialect()
                .getPageSql(sql, page, new org.apache.ibatis.cache.CacheKey()).toLowerCase(Locale.ROOT);
        assertTrue(mysql.contains("order by t.id desc") && mysql.contains("limit ?, ?"), mysql);
        assertTrue(postgre.contains("order by t.id desc") && postgre.contains("limit ? offset ?"), postgre);
        CCJSqlParserUtil.parse(mysql);
        CCJSqlParserUtil.parse(postgre);
    }

    @Test
    void acceptsSenderOnlyIncludingHistoricalMemberAndTrimsId() {
        var query = query(42L, " ");
        query.getQuery().setSenderId("  former-member  ");
        when(mapper.searchText(42L, null, "former-member", null, null)).thenReturn(List.of());
        biz.searchText(query);
        verify(participants).requireParticipant(42L, "member");
        verify(mapper).searchText(42L, null, "former-member", null, null);
        verifyNoMoreInteractions(participants, mapper);
    }

    @Test
    void combinesKeywordSenderAndInclusiveDateRange() {
        var query = query(42L, "  A%  ");
        query.getQuery().setSenderId(" other ");
        query.getQuery().setStartDate("2024-02-29");
        query.getQuery().setEndDate("2024-02-29");
        var start = LocalDateTime.of(2024, 2, 29, 0, 0);
        var end = LocalDateTime.of(2024, 3, 1, 0, 0);
        when(mapper.searchText(42L, "A!%", "other", start, end)).thenReturn(List.of());
        biz.searchText(query);
        verify(mapper).searchText(42L, "A!%", "other", start, end);
    }

    @Test
    void acceptsSingleSidedDatesWithoutKeyword() {
        var startQuery = query(42L, null);
        startQuery.getQuery().setStartDate("2026-10-03");
        var start = LocalDateTime.of(2026, 10, 3, 0, 0);
        when(mapper.searchText(42L, null, null, start, null)).thenReturn(List.of());
        biz.searchText(startQuery);
        verify(mapper).searchText(42L, null, null, start, null);
        var endQuery = query(42L, null);
        endQuery.getQuery().setEndDate("2026-12-31");
        var end = LocalDateTime.of(2027, 1, 1, 0, 0);
        when(mapper.searchText(42L, null, null, null, end)).thenReturn(List.of());
        biz.searchText(endQuery);
        verify(mapper).searchText(42L, null, null, null, end);
    }

    @Test
    void rejectsInvalidDatesSenderAndUnboundedBlankFiltersBeforeAuthorization() {
        for (String value : new String[]{"2023-02-29", "2026-02-30", "2026-1-01", "2026-13-01", "2026-10-03T00:00:00", "0000-01-01", "9999-12-31"}) {
            var query = query(42L, "hello");
            query.getQuery().setStartDate(value);
            assertThrows(BuzzException.class, () -> biz.searchText(query), value);
            query.getQuery().setStartDate(null);
            query.getQuery().setEndDate(value);
            assertThrows(BuzzException.class, () -> biz.searchText(query), value);
        }
        var query = query(42L, "hello");
        query.getQuery().setStartDate("2026-10-04");
        query.getQuery().setEndDate("2026-10-03");
        assertThrows(BuzzException.class, () -> biz.searchText(query));
        query.getQuery().setStartDate(null);
        query.getQuery().setEndDate(null);
        query.getQuery().setSenderId("a".repeat(101));
        assertThrows(BuzzException.class, () -> biz.searchText(query));
        query.getQuery().setKeyword(" ");
        query.getQuery().setSenderId(" ");
        query.getQuery().setStartDate(" ");
        query.getQuery().setEndDate(" ");
        assertThrows(BuzzException.class, () -> biz.searchText(query));
        verifyNoInteractions(participants, mapper);
    }

    @Test
    void combinedSqlUsesOnlyBoundFiltersAndSupportsTenantAndBothPaginationDialects() throws Exception {
        var configuration = new MybatisConfiguration();
        String resource = "mapper/im/core/ImMessageMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement("com.faber.api.im.core.mapper.ImMessageMapper.searchText");
        var params = new HashMap<String, Object>();
        params.put("conversationId", 42L);
        params.put("escapedKeyword", "A!%");
        params.put("senderId", "' OR 1=1 --");
        params.put("startTime", LocalDateTime.of(2026, 10, 3, 0, 0));
        params.put("endTimeExclusive", LocalDateTime.of(2026, 10, 4, 0, 0));
        var bound = statement.getBoundSql(params);
        TenantContext.setTenantId("tenant-a");
        String sql = new TestableTenantInterceptor().rewrite(bound.getSql()).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("t.tenant_id = 'tenant-a'"), sql);
        assertTrue(sql.contains("t.sender_id = ?"), sql);
        assertTrue(sql.contains("t.crt_time >= ?") && sql.contains("t.crt_time < ?"), sql);
        assertFalse(sql.contains("or 1 = 1"), sql);
        assertEquals(List.of("conversationId", "escapedKeyword", "senderId", "startTime", "endTimeExclusive"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        var page = new com.github.pagehelper.Page<ImMessageSearchRetVo>(2, 20);
        String mysql = new com.github.pagehelper.dialect.helper.MySqlDialect().getPageSql(sql, page, new org.apache.ibatis.cache.CacheKey());
        String postgre = new com.github.pagehelper.dialect.helper.PostgreSqlDialect().getPageSql(sql, page, new org.apache.ibatis.cache.CacheKey());
        CCJSqlParserUtil.parse(mysql);
        CCJSqlParserUtil.parse(postgre);
        params.remove("escapedKeyword");
        params.remove("startTime");
        params.remove("endTimeExclusive");
        var senderOnly = statement.getBoundSql(params);
        assertFalse(senderOnly.getSql().contains("LOWER(t.content)"));
        assertEquals(List.of("conversationId", "senderId"), senderOnly.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    private BasePageQuery<ImMessageSearchTextReqVo> query(Long id, String keyword) {
        var request = new ImMessageSearchTextReqVo();
        request.setConversationId(id);
        request.setKeyword(keyword);
        var query = new BasePageQuery<ImMessageSearchTextReqVo>();
        query.setQuery(request);
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
