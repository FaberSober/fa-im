package com.faber.api.im.core.biz;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.entity.ImParticipant;
import com.faber.api.im.core.mapper.ImConversationMapper;
import com.faber.api.im.core.mapper.ImMessageMapper;
import com.faber.core.constant.FaSetting;
import com.faber.core.context.TenantContext;
import com.faber.core.exception.BuzzException;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImConversationReadTest {
    private final ImConversationBiz biz = new ImConversationBiz();
    private final ImConversationMapper conversations = mock(ImConversationMapper.class);
    private final ImParticipantBiz participants = mock(ImParticipantBiz.class);
    private final ImMessageBiz messages = mock(ImMessageBiz.class);
    private final FaSetting settings = mock(FaSetting.class);
    private final LambdaQueryChainWrapper<ImMessage> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
    private final LambdaUpdateChainWrapper<ImParticipant> update = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
    private final ImParticipant participant = new ImParticipant();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("tenant-a");
        ReflectionTestUtils.setField(biz, "baseMapper", conversations);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", participants);
        ReflectionTestUtils.setField(biz, "imMessageBiz", messages);
        ReflectionTestUtils.setField(biz, "faSetting", settings);
        when(settings.isTenantEnabled()).thenReturn(true);
        when(conversations.lockForSend(42L)).thenReturn(42L);
        when(participants.requireParticipantForUpdate(42L, "reader")).thenReturn(participant);
        when(messages.lambdaQuery()).thenReturn(query);
        when(participants.lambdaUpdate()).thenReturn(update);
        doReturn(query).when(query).eq(any(), any());
        doReturn(query).when(query).orderByDesc(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class));
        doReturn(update).when(update).eq(any(), any());
        doReturn(update).when(update).set(any(), any());
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void preciseCursorPreservesMessagesArrivingAfterDisplayedMessage() {
        participant.setLastReadMessageId(6L);
        when(query.one()).thenReturn(message(8L));
        when(messages.countUnreadForUpdate(42L, "reader", 8L)).thenReturn(2L);
        biz.updateConversationRead("reader", 42L, 8L);
        InOrder order = inOrder(conversations, participants, messages, query, update);
        order.verify(participants).requireParticipant(42L, "reader");
        order.verify(conversations).lockForSend(42L);
        order.verify(participants).requireParticipantForUpdate(42L, "reader");
        order.verify(messages).lambdaQuery();
        order.verify(query).eq(any(), eq(42L));
        order.verify(query).eq(any(), eq(8L));
        order.verify(query).last("FOR UPDATE");
        order.verify(query).one();
        order.verify(messages).countUnreadForUpdate(42L, "reader", 8L);
        order.verify(participants).lambdaUpdate();
        verify(update).set(any(), eq(2));
        verify(update).set(any(), eq(8L));
        verify(update).update();
    }

    @Test
    void staleAcknowledgementCannotMoveCursorBackwards() {
        participant.setLastReadMessageId(10L);
        when(query.one()).thenReturn(message(8L));
        when(messages.countUnreadForUpdate(42L, "reader", 10L)).thenReturn(1L);
        biz.updateConversationRead("reader", 42L, 8L);
        verify(messages).countUnreadForUpdate(42L, "reader", 10L);
        verify(update).set(any(), eq(10L));
        verify(update).set(any(), eq(1));
    }

    @Test
    void missingOrForeignTargetDoesNotUpdateParticipant() {
        when(query.one()).thenReturn(null);
        assertThrows(BuzzException.class, () -> biz.updateConversationRead("reader", 42L, 99L));
        verify(query).eq(any(), eq(42L));
        verify(query).eq(any(), eq(99L));
        verify(participants, never()).lambdaUpdate();
        verify(messages, never()).countUnreadForUpdate(any(), any(), any());
    }

    @Test
    void legacyRequestUsesLatestMessageAfterLocking() {
        when(query.one()).thenReturn(message(12L));
        when(messages.countUnreadForUpdate(42L, "reader", 12L)).thenReturn(0L);
        biz.updateConversationRead("reader", 42L);
        verify(query).last("LIMIT 1 FOR UPDATE");
        verify(messages).countUnreadForUpdate(42L, "reader", 12L);
        verify(update).set(any(), eq(12L));
        verify(update).set(any(), eq(0));
    }

    @Test
    void emptyLegacyHistoryKeepsExistingCursor() {
        participant.setLastReadMessageId(10L);
        when(query.one()).thenReturn(null);
        when(messages.countUnreadForUpdate(42L, "reader", 10L)).thenReturn(0L);
        biz.updateConversationRead("reader", 42L);
        verify(update).set(any(), eq(10L));
    }

    @Test
    void unauthorizedMemberCannotReadMessagesOrUpdate() {
        when(participants.requireParticipantForUpdate(42L, "reader")).thenThrow(new BuzzException("无权访问该会话"));
        assertThrows(BuzzException.class, () -> biz.updateConversationRead("reader", 42L, 8L));
        verifyNoInteractions(messages, update);
    }

    @Test
    void unauthorizedMemberCannotLockConversation() {
        when(participants.requireParticipant(42L, "reader")).thenThrow(new BuzzException("无权访问该会话"));
        assertThrows(BuzzException.class, () -> biz.updateConversationRead("reader", 42L, 8L));
        verifyNoInteractions(conversations, messages, update);
    }

    @Test
    void invalidIdsOrMissingConversationAreRejected() {
        assertThrows(BuzzException.class, () -> biz.updateConversationRead("reader", 42L, 0L));
        assertThrows(BuzzException.class, () -> biz.updateConversationRead("reader", 0L, 8L));
        verifyNoInteractions(conversations, participants, messages);
        when(conversations.lockForSend(42L)).thenReturn(null);
        assertThrows(BuzzException.class, () -> biz.updateConversationRead("reader", 42L, 8L));
        verify(participants, never()).requireParticipantForUpdate(any(), any());
        verifyNoInteractions(messages);
    }

    @Test
    void missingTenantIsRejectedBeforeDatabaseAccess() {
        TenantContext.clear();
        assertThrows(BuzzException.class, () -> biz.updateConversationRead("reader", 42L, 8L));
        verifyNoInteractions(conversations, participants, messages);
    }

    @Test
    void unreadQueryLocksOnlyNonDeletedOtherSendersAfterCursor() throws Exception {
        String sql = ImMessageMapper.class.getMethod("countUnreadForUpdate", Long.class, String.class, Long.class)
            .getAnnotation(Select.class).value()[0];
        var interceptor = new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
            new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
                public net.sf.jsqlparser.expression.Expression getTenantId() {
                    return new net.sf.jsqlparser.expression.StringValue("tenant-a");
                }
            });
        String parsed = interceptor.parserSingle(sql.replaceAll("#\\{[^}]+}", "?"), null);
        assertTrue(parsed.contains("FOR UPDATE"));
        assertTrue(parsed.contains("sender_id !="));
        assertTrue(parsed.contains("id >"));
        assertTrue(parsed.contains("deleted = false"));
        assertTrue(parsed.contains("tenant_id = 'tenant-a'"));
    }

    private ImMessage message(long id) {
        ImMessage message = new ImMessage();
        message.setId(id);
        message.setConversationId(42L);
        return message;
    }
}
