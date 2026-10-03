package com.faber.api.im.core.biz;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.faber.api.im.core.entity.ImParticipant;
import com.faber.api.im.core.mapper.ImConversationMapper;
import com.faber.api.im.core.vo.req.ImConversationUpdatePinnedReqVo;
import com.faber.core.constant.FaSetting;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.context.TenantContext;
import com.faber.core.exception.BuzzException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImConversationPinnedTest {
    private final ImConversationBiz biz = new ImConversationBiz();
    private final ImConversationMapper conversations = mock(ImConversationMapper.class);
    private final ImParticipantBiz participants = mock(ImParticipantBiz.class);
    private final FaSetting settings = mock(FaSetting.class);
    private final LambdaUpdateChainWrapper<ImParticipant> update = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
    private final ImParticipant participant = new ImParticipant();

    @BeforeEach
    void setUp() {
        BaseContextHandler.setUserId("me");
        TenantContext.setTenantId("tenant-a");
        ReflectionTestUtils.setField(biz, "baseMapper", conversations);
        ReflectionTestUtils.setField(biz, "imParticipantBiz", participants);
        ReflectionTestUtils.setField(biz, "faSetting", settings);
        when(settings.isTenantEnabled()).thenReturn(true);
        when(conversations.lockForSend(42L)).thenReturn(42L);
        participant.setId(7L);
        when(participants.requireParticipantForUpdate(42L, "me")).thenReturn(participant);
        when(participants.lambdaUpdate()).thenReturn(update);
        doReturn(update).when(update).eq(any(), any());
        doReturn(update).when(update).set(any(), any());
    }

    @AfterEach
    void clearContext() { BaseContextHandler.remove(); }

    @Test
    void pinOnlyUpdatesCurrentUsersParticipantAfterCurrentRead() {
        biz.updateConversationPinned(request(true));
        var order = inOrder(conversations, participants, update);
        order.verify(participants).requireParticipant(42L, "me");
        order.verify(conversations).lockForSend(42L);
        order.verify(participants).requireParticipantForUpdate(42L, "me");
        order.verify(participants).lambdaUpdate();
        order.verify(update).eq(any(), eq(7L));
        order.verify(update).eq(any(), eq(42L));
        order.verify(update).eq(any(), eq("me"));
        verify(update).set(any(), eq(true));
        verify(update).set(any(), isA(Date.class));
        verify(update).update();
    }

    @Test
    void repeatPinPreservesOriginalTimestampAndDoesNotWrite() {
        Date original = new Date(1000L);
        participant.setPinned(true);
        participant.setPinnedTime(original);
        biz.updateConversationPinned(request(true));
        assertSame(original, participant.getPinnedTime());
        verify(participants, never()).lambdaUpdate();
    }

    @Test
    void unpinClearsTimeAndRepeatedUnpinIsNoOp() {
        participant.setPinned(true);
        participant.setPinnedTime(new Date());
        biz.updateConversationPinned(request(false));
        verify(update).set(any(), eq(false));
        verify(update).set(any(), isNull());
        clearInvocations(participants, update);
        participant.setPinned(false);
        participant.setPinnedTime(null);
        biz.updateConversationPinned(request(false));
        verify(participants, never()).lambdaUpdate();
    }

    @Test
    void nonMemberCannotLockOrUpdateConversation() {
        when(participants.requireParticipant(42L, "me")).thenThrow(new BuzzException("无权访问该会话"));
        assertThrows(BuzzException.class, () -> biz.updateConversationPinned(request(true)));
        verifyNoInteractions(conversations, update);
    }

    @Test
    void concurrentRemovedParticipantCannotWriteAfterConversationLock() {
        when(participants.requireParticipantForUpdate(42L, "me")).thenThrow(new BuzzException("无权访问该会话"));
        assertThrows(BuzzException.class, () -> biz.updateConversationPinned(request(true)));
        verify(participants, never()).lambdaUpdate();
    }

    @Test
    void deletedConversationCannotBePinned() {
        when(conversations.lockForSend(42L)).thenReturn(null);
        assertThrows(BuzzException.class, () -> biz.updateConversationPinned(request(true)));
        verify(participants, never()).requireParticipantForUpdate(any(), any());
        verifyNoInteractions(update);
    }

    @Test
    void missingTenantOrUserAndInvalidRequestAreRejectedBeforeDatabaseAccess() {
        TenantContext.clear();
        assertThrows(BuzzException.class, () -> biz.updateConversationPinned(request(true)));
        TenantContext.setTenantId("tenant-a");
        assertThrows(BuzzException.class, () -> biz.updateConversationPinned(null));
        assertThrows(BuzzException.class, () -> biz.updateConversationPinned(request(null)));
        ImConversationUpdatePinnedReqVo invalid = request(true);
        invalid.setConversationId(0L);
        assertThrows(BuzzException.class, () -> biz.updateConversationPinned(invalid));
        BaseContextHandler.remove();
        TenantContext.setTenantId("tenant-a");
        assertThrows(BuzzException.class, () -> biz.updateConversationPinned(request(true)));
        verifyNoInteractions(participants, conversations, update);
    }

    @Test
    void requestValidationRequiresBooleanAndPositiveConversation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(request(null)).isEmpty());
            ImConversationUpdatePinnedReqVo invalid = request(true);
            invalid.setConversationId(0L);
            assertFalse(validator.validate(invalid).isEmpty());
            assertTrue(validator.validate(request(false)).isEmpty());
        }
    }

    private ImConversationUpdatePinnedReqVo request(Boolean pinned) {
        ImConversationUpdatePinnedReqVo request = new ImConversationUpdatePinnedReqVo();
        request.setConversationId(42L);
        request.setPinned(pinned);
        return request;
    }
}
