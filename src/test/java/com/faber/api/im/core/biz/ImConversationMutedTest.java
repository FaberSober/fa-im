package com.faber.api.im.core.biz;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.faber.api.im.core.entity.ImParticipant;
import com.faber.api.im.core.entity.ImMessage;
import com.baomidou.mybatisplus.annotation.TableField;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import java.nio.charset.StandardCharsets;
import com.faber.api.im.core.mapper.ImConversationMapper;
import com.faber.api.im.core.vo.req.ImConversationUpdateMutedReqVo;
import com.faber.core.constant.FaSetting;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.context.TenantContext;
import com.faber.core.exception.BuzzException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImConversationMutedTest {
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
    void muteOnlyUpdatesCurrentUsersParticipantAfterCurrentRead() {
        biz.updateConversationMuted(request(true));
        var order = inOrder(conversations, participants, update);
        order.verify(participants).requireParticipant(42L, "me");
        order.verify(conversations).lockForSend(42L);
        order.verify(participants).requireParticipantForUpdate(42L, "me");
        order.verify(participants).lambdaUpdate();
        order.verify(update).eq(any(), eq(7L));
        order.verify(update).eq(any(), eq(42L));
        order.verify(update).eq(any(), eq("me"));
        verify(update).set(any(), eq(true));
        verify(update, times(1)).set(any(), any());
        verify(update).update();
    }

    @Test
    void repeatedMuteAndUnmuteAreNoOps() {
        participant.setMuted(true);
        biz.updateConversationMuted(request(true));
        verify(participants, never()).lambdaUpdate();
        participant.setMuted(false);
        biz.updateConversationMuted(request(false));
        verify(participants, never()).lambdaUpdate();
    }

    @Test
    void unmuteOnlyChangesFlagAndRetainsUnreadAndReadCursor() {
        participant.setMuted(true);
        participant.setUnreadCount(12);
        participant.setLastReadMessageId(23L);
        biz.updateConversationMuted(request(false));
        verify(update).set(any(), eq(false));
        verify(update, times(1)).set(any(), any());
        assertEquals(12, participant.getUnreadCount());
        assertEquals(23L, participant.getLastReadMessageId());
    }

    @Test
    void nonMemberCannotLockOrUpdateConversation() {
        when(participants.requireParticipant(42L, "me")).thenThrow(new BuzzException("无权访问该会话"));
        assertThrows(BuzzException.class, () -> biz.updateConversationMuted(request(true)));
        verifyNoInteractions(conversations, update);
    }

    @Test
    void concurrentRemovedParticipantCannotWriteAfterConversationLock() {
        when(participants.requireParticipantForUpdate(42L, "me")).thenThrow(new BuzzException("无权访问该会话"));
        assertThrows(BuzzException.class, () -> biz.updateConversationMuted(request(true)));
        verify(participants, never()).lambdaUpdate();
    }

    @Test
    void deletedConversationCannotBeMuted() {
        when(conversations.lockForSend(42L)).thenReturn(null);
        assertThrows(BuzzException.class, () -> biz.updateConversationMuted(request(true)));
        verify(participants, never()).requireParticipantForUpdate(any(), any());
        verifyNoInteractions(update);
    }

    @Test
    void missingTenantOrUserAndInvalidRequestAreRejectedBeforeDatabaseAccess() {
        TenantContext.clear();
        assertThrows(BuzzException.class, () -> biz.updateConversationMuted(request(true)));
        TenantContext.setTenantId("tenant-a");
        assertThrows(BuzzException.class, () -> biz.updateConversationMuted(null));
        assertThrows(BuzzException.class, () -> biz.updateConversationMuted(request(null)));
        ImConversationUpdateMutedReqVo invalid = request(true);
        invalid.setConversationId(0L);
        assertThrows(BuzzException.class, () -> biz.updateConversationMuted(invalid));
        BaseContextHandler.remove();
        TenantContext.setTenantId("tenant-a");
        assertThrows(BuzzException.class, () -> biz.updateConversationMuted(request(true)));
        verifyNoInteractions(participants, conversations, update);
    }

    @Test
    void requestValidationRequiresBooleanAndPositiveConversation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(request(null)).isEmpty());
            ImConversationUpdateMutedReqVo invalid = request(true);
            invalid.setConversationId(0L);
            assertFalse(validator.validate(invalid).isEmpty());
            assertTrue(validator.validate(request(false)).isEmpty());
        }
    }

    @Test
    void migrationDialectsHaveEquivalentFalseDefaultsAndNotificationIsNotPersistent() throws Exception {
        for (String dialect : new String[]{"mysql", "postgre"}) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(
                    "sql/fa-im/" + dialect + "/1.0.6_im_conversation_muted.sql")) {
                assertNotNull(stream);
                String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(sql.contains("@@ver: 1_000_006"));
                assertTrue(sql.contains(dialect.equals("mysql") ? "tinyint(1) NOT NULL DEFAULT 0" : "boolean NOT NULL DEFAULT false"));
                assertFalse(CCJSqlParserUtil.parseStatements(sql).isEmpty());
            }
        }
        assertFalse(ImMessage.class.getDeclaredField("notificationEnabled").getAnnotation(TableField.class).exist());
    }

    private ImConversationUpdateMutedReqVo request(Boolean muted) {
        ImConversationUpdateMutedReqVo request = new ImConversationUpdateMutedReqVo();
        request.setConversationId(42L);
        request.setMuted(muted);
        return request;
    }
}
