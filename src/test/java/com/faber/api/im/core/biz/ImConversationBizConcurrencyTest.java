package com.faber.api.im.core.biz;

import cn.hutool.json.JSONArray;
import com.faber.api.base.admin.biz.UserBiz;
import com.faber.api.base.admin.entity.User;
import com.faber.api.im.core.entity.ImConversation;
import com.faber.api.im.core.vo.req.ImConversationCreateNewSingleReqVo;
import com.faber.core.context.BaseContextHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImConversationBizConcurrencyTest {

    private static final String CURRENT_USER_ID = "user-z";
    private static final String TARGET_USER_ID = "user-a";
    private static final String SINGLE_KEY = "user-a,user-z";

    private final ImConversationBiz biz = spy(new ImConversationBiz());
    private final ImParticipantBiz participantBiz = mock(ImParticipantBiz.class);
    private final UserBiz userBiz = mock(UserBiz.class);

    @BeforeEach
    void setUp() {
        BaseContextHandler.setUserId(CURRENT_USER_ID);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", participantBiz);
        ReflectionTestUtils.setField(biz, "userBiz", userBiz);
    }

    @AfterEach
    void clearUserContext() {
        BaseContextHandler.remove();
    }

    @Test
    void shouldReturnConversationCreatedByConcurrentRequestAfterUniqueKeyConflict() {
        ImConversation createdByOtherRequest = new ImConversation();
        createdByOtherRequest.setId(77L);
        User targetUser = new User();
        targetUser.setName("target");
        ImConversationCreateNewSingleReqVo request = new ImConversationCreateNewSingleReqVo();
        request.setToUserId(TARGET_USER_ID);

        doReturn(null).doReturn(createdByOtherRequest).when(biz).findSingleByKey(SINGLE_KEY);
        doReturn(new JSONArray()).when(biz).getUserImgs(anyList());
        when(userBiz.getById(TARGET_USER_ID)).thenReturn(targetUser);
        doThrow(new DuplicateKeyException("single chat already exists"))
                .when(biz).save(any(ImConversation.class));

        ImConversation result = biz.createNewSingle(request);

        assertSame(createdByOtherRequest, result);
        verify(biz, times(2)).findSingleByKey(SINGLE_KEY);
        var savedConversation = forClass(ImConversation.class);
        verify(biz).save(savedConversation.capture());
        assertEquals(SINGLE_KEY, savedConversation.getValue().getSingleKey());
        verifyNoInteractions(participantBiz);
    }

}
