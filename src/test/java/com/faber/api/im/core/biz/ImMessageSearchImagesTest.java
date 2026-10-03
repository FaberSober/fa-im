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

class ImMessageSearchImagesTest {
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
    void acceptsUnfilteredAndBlankKeywordImageBrowsing() {
        when(mapper.searchImages(42L, null, null, null)).thenReturn(List.of());
        for (String keyword : new String[]{null, "", "  "}) {
            assertDoesNotThrow(() -> biz.searchImages(query(42L, keyword)));
        }
        verify(participants, times(3)).requireParticipant(42L, "member");
        verify(mapper, times(3)).searchImages(42L, null, null, null);
        verifyNoMoreInteractions(participants, mapper);
    }

    @Test
    void rejectsKeywordsInvalidConversationAndPaginationBeforeAccess() {
        assertThrows(BuzzException.class, () -> biz.searchImages(null));
        assertThrows(BuzzException.class, () -> biz.searchImages(new BasePageQuery<>()));
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertThrows(BuzzException.class, () -> biz.searchImages(query(id, null)));
        }
        for (String keyword : new String[]{"hello", "%", "_", "  image  "}) {
            assertThrows(BuzzException.class, () -> biz.searchImages(query(42L, keyword)));
        }
        for (int size : new int[]{0, -1, 101}) {
            var q = query(42L, null);
            q.setPageSize(size);
            assertThrows(BuzzException.class, () -> biz.searchImages(q));
        }
        var q = query(42L, null);
        q.setCurrent(0);
        assertThrows(BuzzException.class, () -> biz.searchImages(q));
        verifyNoInteractions(participants, mapper);
    }

    @Test
    void deniedMembershipStopsBeforeSearch() {
        doThrow(new BuzzException("无权访问该会话")).when(participants).requireParticipant(42L, "member");
        assertThrows(BuzzException.class, () -> biz.searchImages(query(42L, null)));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingTenantStopsBeforeSearch() {
        var actualParticipants = new ImParticipantBiz();
        var settings = mock(FaSetting.class);
        when(settings.isTenantEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(actualParticipants, "faSetting", settings);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", actualParticipants);
        assertThrows(BuzzException.class, () -> biz.searchImages(query(42L, null)));
        verifyNoInteractions(mapper);
    }

    @Test
    void combinesHistoricalSenderInclusiveDatesAndRequestedPageWithoutReadMutation() {
        var q = query(42L, " ");
        q.getQuery().setSenderId("  former-member  ");
        q.getQuery().setStartDate("2024-02-29");
        q.getQuery().setEndDate("2024-02-29");
        q.setCurrent(3);
        q.setPageSize(100);
        var start = LocalDateTime.of(2024, 2, 29, 0, 0);
        var end = LocalDateTime.of(2024, 3, 1, 0, 0);
        var image = new ImMessageSearchRetVo();
        image.setId(99L);
        image.setSenderName("张三");
        image.setSenderUserImg("avatar-file");
        image.setFileId("image-file");
        when(mapper.searchImages(42L, "former-member", start, end)).thenAnswer(invocation -> {
            var page = PageHelper.<ImMessageSearchRetVo>getLocalPage();
            assertEquals(3, page.getPageNum());
            assertEquals(100, page.getPageSize());
            page.setTotal(201);
            page.add(image);
            return page;
        });
        var result = biz.searchImages(q);
        assertEquals(List.of(image), result.getData().getRows());
        assertEquals(201, result.getData().getTotal());
        assertEquals(3, result.getData().getPagination().getCurrent());
        var order = inOrder(participants, mapper);
        order.verify(participants).requireParticipant(42L, "member");
        order.verify(mapper).searchImages(42L, "former-member", start, end);
        verifyNoMoreInteractions(participants, mapper);
    }

    @Test
    void acceptsSingleSidedDateFilters() {
        var q = query(42L, null);
        q.getQuery().setStartDate("2026-10-03");
        var start = LocalDateTime.of(2026, 10, 3, 0, 0);
        when(mapper.searchImages(42L, null, start, null)).thenReturn(List.of());
        biz.searchImages(q);
        verify(mapper).searchImages(42L, null, start, null);
        q.getQuery().setStartDate(null);
        q.getQuery().setEndDate("2026-12-31");
        var end = LocalDateTime.of(2027, 1, 1, 0, 0);
        when(mapper.searchImages(42L, null, null, end)).thenReturn(List.of());
        biz.searchImages(q);
        verify(mapper).searchImages(42L, null, null, end);
    }

    @Test
    void rejectsInvalidDatesRangesAndSenderBeforeAuthorization() {
        for (String value : new String[]{"2023-02-29", "2026-02-30", "2026-1-01", "2026-13-01", "2026-10-03T00:00:00", "0000-01-01", "9999-12-31"}) {
            var q = query(42L, null);
            q.getQuery().setStartDate(value);
            assertThrows(BuzzException.class, () -> biz.searchImages(q), value);
            q.getQuery().setStartDate(null);
            q.getQuery().setEndDate(value);
            assertThrows(BuzzException.class, () -> biz.searchImages(q), value);
        }
        var q = query(42L, null);
        q.getQuery().setStartDate("2026-10-04");
        q.getQuery().setEndDate("2026-10-03");
        assertThrows(BuzzException.class, () -> biz.searchImages(q));
        q.getQuery().setStartDate(null);
        q.getQuery().setEndDate(null);
        q.getQuery().setSenderId("a".repeat(101));
        assertThrows(BuzzException.class, () -> biz.searchImages(q));
        verifyNoInteractions(participants, mapper);
    }

    @Test
    void sqlRestrictsImageTypeBindsFiltersAndSupportsTenantAndBothPaginationDialects() throws Exception {
        var configuration = new MybatisConfiguration();
        String resource = "mapper/im/core/ImMessageMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement("com.faber.api.im.core.mapper.ImMessageMapper.searchImages");
        var params = new HashMap<String, Object>();
        params.put("conversationId", 42L);
        params.put("senderId", "' OR 1=1 --");
        params.put("startTime", LocalDateTime.of(2026, 10, 3, 0, 0));
        params.put("endTimeExclusive", LocalDateTime.of(2026, 10, 4, 0, 0));
        var bound = statement.getBoundSql(params);
        TenantContext.setTenantId("tenant-a");
        String sql = new TestableTenantInterceptor().rewrite(bound.getSql()).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("t.tenant_id = 'tenant-a'"), sql);
        assertTrue(sql.contains("t.conversation_id = ?"), sql);
        assertTrue(sql.contains("t.deleted = false") && sql.contains("t.type = 2"), sql);
        assertTrue(sql.contains("t.is_withdrawn = false or t.is_withdrawn is null"), sql);
        assertTrue(sql.contains("u.name as sender_name") && sql.contains("u.img as sender_user_img"), sql);
        assertTrue(sql.contains("t.sender_id = ?"), sql);
        assertTrue(sql.contains("t.crt_time >= ?") && sql.contains("t.crt_time < ?"), sql);
        assertFalse(sql.contains("or 1 = 1") || sql.contains("like"), sql);
        assertEquals(List.of("conversationId", "senderId", "startTime", "endTimeExclusive"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        var page = new com.github.pagehelper.Page<ImMessageSearchRetVo>(2, 20);
        String mysql = new com.github.pagehelper.dialect.helper.MySqlDialect().getPageSql(sql, page, new org.apache.ibatis.cache.CacheKey()).toLowerCase(Locale.ROOT);
        String postgre = new com.github.pagehelper.dialect.helper.PostgreSqlDialect().getPageSql(sql, page, new org.apache.ibatis.cache.CacheKey()).toLowerCase(Locale.ROOT);
        assertTrue(mysql.contains("order by t.id desc") && mysql.contains("limit ?, ?"), mysql);
        assertTrue(postgre.contains("order by t.id desc") && postgre.contains("limit ? offset ?"), postgre);
        CCJSqlParserUtil.parse(mysql);
        CCJSqlParserUtil.parse(postgre);
        params.clear();
        params.put("conversationId", 42L);
        var unfiltered = statement.getBoundSql(params);
        assertEquals(List.of("conversationId"), unfiltered.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        assertFalse(unfiltered.getSql().contains("crt_time >=") || unfiltered.getSql().contains("t.sender_id = ?"));
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
