package com.faber.api.im.core.biz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.faber.api.im.core.entity.ImParticipant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faber.api.base.admin.biz.UserBiz;
import com.faber.api.base.admin.entity.User;
import com.faber.api.im.core.entity.ImConversation;
import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.enums.ImMessageTypeEnum;
import com.faber.api.im.core.mapper.ImConversationMapper;
import com.faber.api.im.core.vo.req.ImConversationSendMsgReqVo;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.exception.BuzzException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImConversationSendMsgTest {
    private final ImConversationMapper mapper = mock(ImConversationMapper.class);
    private final ImParticipantBiz participants = mock(ImParticipantBiz.class, RETURNS_DEEP_STUBS);
    private final ImMessageBiz messages = mock(ImMessageBiz.class);
    private final UserBiz users = mock(UserBiz.class);
    private final ImConversationBiz biz = new ImConversationBiz();
    private LambdaQueryChainWrapper<ImParticipant> participantQuery;

    @BeforeEach
    void setUp() {
        BaseContextHandler.setUserId("sender-1");
        BaseContextHandler.setName("发送者");
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), ImConversation.class);
        ReflectionTestUtils.setField(biz, "baseMapper", mapper);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", participants);
        ReflectionTestUtils.setField(biz, "imMessageBiz", messages);
        ReflectionTestUtils.setField(biz, "userBiz", users);
        when(mapper.lockForSend(42L)).thenReturn(42L);
        User user = new User();
        user.setImg("avatar");
        when(users.getLoginUser()).thenReturn(user);
        participantQuery = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(participants.lambdaQuery()).thenReturn(participantQuery);
        doReturn(participantQuery).when(participantQuery).eq(any(), any());
        when(participantQuery.list()).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void clearContext() {
        BaseContextHandler.remove();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void retryReturnsSameMessageAndOnlyUpdatesUnreadOnce() {
        AtomicReference<ImMessage> saved = new AtomicReference<>();
        when(messages.findClientMessage(42L, "sender-1", "mobile_123")).thenAnswer(invocation -> saved.get());
        when(messages.save(any(ImMessage.class))).thenAnswer(invocation -> {
            ImMessage message = invocation.getArgument(0);
            message.setId(7L);
            saved.set(message);
            return true;
        });
        ImParticipant recipient = new ImParticipant();
        recipient.setUserId("recipient-2");
        when(participantQuery.list()).thenReturn(List.of(recipient));
        TransactionSynchronizationManager.initSynchronization();
        ImMessage first = biz.sendMsg(request("mobile_123", "hello 😀"));
        ImMessage retry = biz.sendMsg(request("mobile_123", "hello 😀"));
        assertSame(first, retry);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size(), "重试不得重复注册广播");
        assertEquals("mobile_123", retry.getClientMessageId());
        verify(messages, times(1)).save(any(ImMessage.class));
        verify(mapper, times(1)).updateUnreadByConvId(42L, "sender-1");
        verify(participants, times(2)).requireParticipant(42L, "sender-1");
    }

    @Test
    void sameKeyWithDifferentContentIsRejectedWithoutUnreadOrInsert() {
        ImMessage existing = new ImMessage();
        existing.setType(ImMessageTypeEnum.TEXT);
        existing.setContent("original");
        when(messages.findClientMessage(42L, "sender-1", "key")).thenReturn(existing);
        assertThrows(BuzzException.class, () -> biz.sendMsg(request("key", "different")));
        verify(messages, never()).save(any());
        verify(mapper, never()).updateUnreadByConvId(any(), any());
    }

    @Test
    void legacyRequestWithoutKeyStillSends() {
        assertNull(biz.sendMsg(request(null, "legacy")).getClientMessageId());
        verify(messages).save(any(ImMessage.class));
        verify(messages, never()).findClientMessage(any(), any(), any());
        verify(mapper).updateUnreadByConvId(42L, "sender-1");
    }

    @Test
    void permissionIsCheckedBeforeLookingUpRetry() {
        doThrow(new BuzzException("无权访问该会话")).when(participants).requireParticipant(42L, "sender-1");
        assertThrows(BuzzException.class, () -> biz.sendMsg(request("key", "hello")));
        verifyNoInteractions(mapper, messages);
    }

    @Test
    void invalidKeyIsRejectedBeforeLocking() {
        for (String key : new String[]{"", "has space", "中文", "a".repeat(65)}) {
            assertThrows(BuzzException.class, () -> biz.sendMsg(request(key, "hello")));
        }
        verifyNoInteractions(mapper, messages);
    }

    @Test
    void missingConversationCannotSend() {
        when(mapper.lockForSend(42L)).thenReturn(null);
        assertThrows(BuzzException.class, () -> biz.sendMsg(request("key", "hello")));
        verifyNoInteractions(messages);
    }

    private ImConversationSendMsgReqVo request(String key, String content) {
        ImConversationSendMsgReqVo req = new ImConversationSendMsgReqVo();
        req.setConversationId(42L);
        req.setType(ImMessageTypeEnum.TEXT);
        req.setContent(content);
        req.setClientMessageId(key);
        return req;
    }
}
