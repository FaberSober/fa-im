package com.faber.api.im.core.biz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.faber.api.im.core.vo.req.ImConversationListQueryReqVo;
import com.faber.core.config.mybatis.interceptor.FaTenantInterceptor;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.context.TenantContext;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ImConversationListQueryTest {

    @AfterEach
    void clearContext() {
        BaseContextHandler.remove();
    }

    @Test
    void listQueryScopesMessageAndPeerSubqueriesAndSortsByMessageTime() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        String resource = "mapper/im/core/ImConversationMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        BoundSql boundSql = configuration.getMappedStatement("com.faber.api.im.core.mapper.ImConversationMapper.listQuery")
                .getBoundSql(Map.of("userId", "me", "query", new ImConversationListQueryReqVo()));
        BaseContextHandler.setUserId("2");
        TenantContext.setTenantId("tenant-a");
        String sql = new TestableTenantInterceptor().rewrite(boundSql.getSql());
        for (String alias : new String[]{"c", "t", "m", "p", "lm"}) {
            assertTrue(sql.contains(alias + ".tenant_id = 'tenant-a'"), "缺少租户范围：" + alias);
        }
        assertTrue(sql.toLowerCase(Locale.ROOT).contains("m.deleted = false"), sql);
        assertTrue(sql.contains("p.user_id <> ?") || sql.contains("p.user_id != ?"), "头像应排除当前用户");
        assertTrue(sql.contains("ORDER BY COALESCE(lm.crt_time, c.crt_time) DESC, c.id DESC"), sql);
    }

    private static class TestableTenantInterceptor extends FaTenantInterceptor {
        String rewrite(String sql) throws Exception {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            processSelect(select, 0, sql, null);
            return select.toString();
        }
    }
}
