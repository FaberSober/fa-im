package com.faber.api.im.core.biz;

import com.faber.api.im.core.mapper.ImConversationMapper;
import com.faber.core.context.BaseContextHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImConversationBizTest {

    private static final String CURRENT_USER_ID = "reader-7";

    private final ImConversationMapper mapper = mock(ImConversationMapper.class);
    private final ImConversationBiz biz = new ImConversationBiz();

    @BeforeEach
    void setUp() {
        BaseContextHandler.setUserId(CURRENT_USER_ID);
        ReflectionTestUtils.setField(biz, "baseMapper", mapper);
    }

    @AfterEach
    void clearUserContext() {
        BaseContextHandler.remove();
    }

    @Test
    void shouldReadUnreadTotalForCurrentUser() {
        when(mapper.countUnreadByUserId(CURRENT_USER_ID)).thenReturn(6);

        assertEquals(6, biz.getUnreadCount());

        verify(mapper).countUnreadByUserId(CURRENT_USER_ID);
    }

    @Test
    void shouldPreserveZeroUnreadTotal() {
        when(mapper.countUnreadByUserId(CURRENT_USER_ID)).thenReturn(0);

        assertEquals(0, biz.getUnreadCount());

        verify(mapper).countUnreadByUserId(CURRENT_USER_ID);
    }

}
