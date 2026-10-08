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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImMessageSearchFilesTest {
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
    void browsesAllFilesAndEscapesLiteralFilenameWildcards() {
        when(mapper.searchFiles(anyLong(), any(), any(), any(), any(), any())).thenReturn(List.of());
        for (String keyword : new String[]{null, "", "  "}) biz.searchFiles(query(keyword, null));
        verify(mapper, times(3)).searchFiles(42L, null, null, null, null, null);
        biz.searchFiles(query("  计划%_!.PDF  ", " .PDF "));
        verify(mapper).searchFiles(42L, "计划!%!_!!.PDF", "pdf", null, null, null);
        verify(participants, times(4)).requireParticipant(42L, "member");
        verifyNoMoreInteractions(mapper, participants);
    }

    @Test
    void combinesFilenameExtensionHistoricalSenderInclusiveDatesAndPageWithoutReadMutation() {
        var q = query("报告", "DOCX");
        q.getQuery().setSenderId(" former-member ");
        q.getQuery().setStartDate("2024-02-29");
        q.getQuery().setEndDate("2024-02-29");
        q.setCurrent(2);
        q.setPageSize(20);
        var start = LocalDateTime.of(2024, 2, 29, 0, 0);
        var end = LocalDateTime.of(2024, 3, 1, 0, 0);
        var file = new ImMessageSearchRetVo();
        file.setId(99L);
        file.setFileId("file-id");
        when(mapper.searchFiles(42L, "报告", "docx", "former-member", start, end)).thenAnswer(invocation -> {
            var page = PageHelper.<ImMessageSearchRetVo>getLocalPage();
            assertEquals(2, page.getPageNum());
            assertEquals(20, page.getPageSize());
            page.setTotal(21);
            page.add(file);
            return page;
        });
        var result = biz.searchFiles(q);
        assertEquals(List.of(file), result.getData().getRows());
        assertEquals(21, result.getData().getTotal());
        assertEquals(2, result.getData().getPagination().getCurrent());
        var order = inOrder(participants, mapper);
        order.verify(participants).requireParticipant(42L, "member");
        order.verify(mapper).searchFiles(42L, "报告", "docx", "former-member", start, end);
        verifyNoMoreInteractions(participants, mapper);
    }

    @Test
    void rejectsInvalidFiltersAndPagesBeforeAuthorization() {
        assertThrows(BuzzException.class, () -> biz.searchFiles(null));
        assertThrows(BuzzException.class, () -> biz.searchFiles(new BasePageQuery<>()));
        for (Long id : new Long[]{null, 0L, -1L}) {
            var q = query(null, null);
            q.getQuery().setConversationId(id);
            assertThrows(BuzzException.class, () -> biz.searchFiles(q));
        }
        for (String ext : new String[]{".", "pdf,docx", "../pdf", "pdf%", "a".repeat(13), "' OR 1=1 --"}) {
            assertThrows(BuzzException.class, () -> biz.searchFiles(query(null, ext)));
        }
        assertThrows(BuzzException.class, () -> biz.searchFiles(query("a".repeat(101), null)));
        var q = query(null, null);
        q.getQuery().setSenderId("a".repeat(101));
        assertThrows(BuzzException.class, () -> biz.searchFiles(q));
        q.getQuery().setSenderId(null);
        for (String date : new String[]{"2026-02-30", "2026-1-01", "9999-12-31"}) {
            q.getQuery().setStartDate(date);
            assertThrows(BuzzException.class, () -> biz.searchFiles(q));
        }
        q.getQuery().setStartDate("2026-10-08");
        q.getQuery().setEndDate("2026-10-07");
        assertThrows(BuzzException.class, () -> biz.searchFiles(q));
        for (int size : new int[]{0, -1, 101}) {
            var page = query(null, null);
            page.setPageSize(size);
            assertThrows(BuzzException.class, () -> biz.searchFiles(page));
        }
        var page = query(null, null);
        page.setCurrent(0);
        assertThrows(BuzzException.class, () -> biz.searchFiles(page));
        assertThrows(BuzzException.class, () -> biz.searchText(query("text", "pdf")));
        assertThrows(BuzzException.class, () -> biz.searchImages(query(null, "pdf")));
        verifyNoInteractions(participants, mapper);
    }

    @Test
    void deniedMembershipAndMissingTenantStopBeforeSearch() {
        doThrow(new BuzzException("无权访问该会话")).when(participants).requireParticipant(42L, "member");
        assertThrows(BuzzException.class, () -> biz.searchFiles(query(null, null)));
        var actualParticipants = new ImParticipantBiz();
        var settings = mock(FaSetting.class);
        when(settings.isTenantEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(actualParticipants, "faSetting", settings);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", actualParticipants);
        assertThrows(BuzzException.class, () -> biz.searchFiles(query(null, null)));
        verifyNoInteractions(mapper);
    }

    @Test
    void sqlBindsFilenameAndExtensionAndKeepsTenantFileTypeWithdrawalAndBothDialects() throws Exception {
        var configuration = new MybatisConfiguration();
        String resource = "mapper/im/core/ImMessageMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement("com.faber.api.im.core.mapper.ImMessageMapper.searchFiles");
        var params = new HashMap<String, Object>();
        params.put("conversationId", 42L);
        params.put("escapedKeyword", "' OR 1=1 --!%");
        params.put("fileExt", "pdf");
        params.put("senderId", "member");
        params.put("startTime", LocalDateTime.of(2026, 10, 7, 0, 0));
        params.put("endTimeExclusive", LocalDateTime.of(2026, 10, 9, 0, 0));
        var bound = statement.getBoundSql(params);
        TenantContext.setTenantId("tenant-a");
        String sql = new TestableTenantInterceptor().rewrite(bound.getSql()).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("t.tenant_id = 'tenant-a'"), sql);
        assertTrue(sql.contains("t.conversation_id = ?") && sql.contains("t.type = 4"), sql);
        assertTrue(sql.contains("t.deleted = false") && sql.contains("f.deleted = false"), sql);
        assertTrue(sql.contains("t.is_withdrawn = false or t.is_withdrawn is null"), sql);
        assertTrue(sql.contains("t.file_id = f.id") && sql.contains("lower(f.original_filename) like"), sql);
        assertTrue(sql.contains("escape '!'"), sql);
        assertTrue(sql.replaceAll("\\s+", "").contains("lower(replace(trim(f.ext),'.',''))=?"), sql);
        assertTrue(sql.contains("t.sender_id = ?") && sql.contains("t.crt_time >= ?") && sql.contains("t.crt_time < ?"), sql);
        assertFalse(sql.contains("or 1 = 1") || sql.contains("f.tenant_id"), sql);
        assertEquals(List.of("conversationId", "escapedKeyword", "fileExt", "senderId", "startTime", "endTimeExclusive"),
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
        assertEquals(List.of("conversationId"), statement.getBoundSql(params).getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    private BasePageQuery<ImMessageSearchTextReqVo> query(String keyword, String ext) {
        var request = new ImMessageSearchTextReqVo();
        request.setConversationId(42L);
        request.setKeyword(keyword);
        request.setFileExt(ext);
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
