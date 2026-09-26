package com.faber.api.im.core.biz;

import com.faber.api.im.core.mapper.ImMessageMapper;
import com.faber.api.im.core.vo.req.ImMessagePageQueryVo;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.exception.BuzzException;
import com.faber.core.vo.query.BasePageQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ImMessageBizTest {

    private static final String CURRENT_USER_ID = "outsider";

    private final ImParticipantBiz participantBiz = mock(ImParticipantBiz.class);
    private final ImMessageMapper mapper = mock(ImMessageMapper.class);
    private final ImMessageBiz biz = new ImMessageBiz();

    @BeforeEach
    void setUp() {
        BaseContextHandler.setUserId(CURRENT_USER_ID);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", participantBiz);
        ReflectionTestUtils.setField(biz, "baseMapper", mapper);
    }

    @AfterEach
    void clearUserContext() {
        BaseContextHandler.remove();
    }

    @Test
    void shouldRejectMessageHistoryBeforeQueryingWhenUserIsNotAParticipant() {
        long conversationId = 42L;
        doThrow(new BuzzException("无权访问该会话"))
                .when(participantBiz).requireParticipant(conversationId, CURRENT_USER_ID);

        assertThrows(BuzzException.class, () -> biz.pageQuery(pageQuery(conversationId)));

        verify(participantBiz).requireParticipant(conversationId, CURRENT_USER_ID);
        verifyNoInteractions(mapper);
    }

    private BasePageQuery<ImMessagePageQueryVo> pageQuery(Long conversationId) {
        ImMessagePageQueryVo filter = new ImMessagePageQueryVo();
        filter.setConversationId(conversationId);

        BasePageQuery<ImMessagePageQueryVo> query = new BasePageQuery<>();
        query.setQuery(filter);
        return query;
    }

}
