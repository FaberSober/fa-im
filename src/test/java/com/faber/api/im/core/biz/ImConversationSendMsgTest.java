package com.faber.api.im.core.biz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.faber.api.im.core.entity.ImParticipant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faber.api.base.admin.biz.UserBiz;
import com.faber.api.base.admin.biz.FileSaveBiz;
import com.faber.api.base.admin.entity.FileSave;
import cn.hutool.json.JSONUtil;
import com.faber.api.base.admin.entity.User;
import com.faber.api.im.core.entity.ImConversation;
import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.enums.ImMessageTypeEnum;
import com.faber.api.im.core.mapper.ImConversationMapper;
import com.faber.api.im.core.vo.req.ImConversationSendMsgReqVo;
import com.faber.core.context.BaseContextHandler;
import com.faber.config.websocket.WsHolder;
import com.faber.core.enums.WsTypeEnum;
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
    private final FileSaveBiz files = mock(FileSaveBiz.class);
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
        ReflectionTestUtils.setField(biz, "fileSaveBiz", files);
        when(mapper.lockForSend(42L)).thenReturn(42L);
        User user = new User();
        user.setImg("avatar");
        when(users.getLoginUser()).thenReturn(user);
        participantQuery = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(participants.lambdaQuery()).thenReturn(participantQuery);
        doReturn(participantQuery).when(participantQuery).eq(any(), any());
        doReturn(participantQuery).when(participantQuery).last(anyString());
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

    @Test
    void allRecipientsReceiveDataButMutedRecipientsDisableOnlyNotification() {
        ImParticipant sender = new ImParticipant();
        sender.setUserId("sender-1");
        ImParticipant normal = new ImParticipant();
        normal.setUserId("normal");
        ImParticipant legacy = new ImParticipant();
        legacy.setUserId("legacy");
        ImParticipant muted = new ImParticipant();
        muted.setUserId("muted");
        muted.setMuted(true);
        when(participantQuery.list()).thenReturn(List.of(sender, normal, legacy, muted));
        when(messages.save(any(ImMessage.class))).thenAnswer(invocation -> {
            ImMessage message = invocation.getArgument(0);
            message.setId(7L);
            return true;
        });
        TransactionSynchronizationManager.initSynchronization();
        ImMessage result = biz.sendMsg(request("notification_test", "hello"));
        assertNull(result.getNotificationEnabled(), "发送响应与持久化对象不能被接收者设置污染");
        assertEquals(2, TransactionSynchronizationManager.getSynchronizations().size());
        var order = inOrder(mapper, participants, participantQuery);
        order.verify(participants).requireParticipant(42L, "sender-1");
        order.verify(mapper).lockForSend(42L);
        order.verify(mapper).updateUnreadByConvId(42L, "sender-1");
        order.verify(participants).lambdaQuery();
        order.verify(participantQuery).eq(any(), eq(42L));
        order.verify(participantQuery).last("FOR UPDATE");
        order.verify(participantQuery).list();
        try (var sockets = mockStatic(WsHolder.class)) {
            sockets.verifyNoInteractions();
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            sockets.verify(() -> WsHolder.sendMessage(eq(List.of("normal", "legacy")), eq(WsTypeEnum.IM),
                argThat(event -> event instanceof ImMessage message && message != result
                    && Boolean.TRUE.equals(message.getNotificationEnabled()) && message.getId().equals(7L)
                    && message.getContent().equals("hello"))));
            sockets.verify(() -> WsHolder.sendMessage(eq(List.of("muted")), eq(WsTypeEnum.IM),
                argThat(event -> event instanceof ImMessage message && message != result
                    && Boolean.FALSE.equals(message.getNotificationEnabled()) && message.getId().equals(7L)
                    && message.getSenderUserImg().equals("avatar"))));
            sockets.verifyNoMoreInteractions();
        }
    }

    @Test
    void imageMetadataComesFromAttachmentAndRetryDoesNotInsertTwice() {
        FileSave file = imageFile(".JPG", "image/jpeg", 20L * 1024 * 1024);
        when(files.getById("image-1")).thenReturn(file);
        AtomicReference<ImMessage> saved = new AtomicReference<>();
        when(messages.findClientMessage(42L, "sender-1", "image_retry")).thenAnswer(call -> saved.get());
        when(messages.save(any())).thenAnswer(call -> {
            ImMessage message = call.getArgument(0);
            message.setId(8L);
            saved.set(message);
            return true;
        });
        ImConversationSendMsgReqVo req = imageRequest("image_retry");
        req.setContent("{\"fileId\":\"image-1\",\"fileName\":\"forged.svg\",\"fileSize\":1,\"ext\":\"svg\"}");
        ImMessage first = biz.sendMsg(req);
        var content = JSONUtil.parseObj(first.getContent());
        assertEquals("image-1", first.getFileId());
        assertEquals("真实图片.jpg", content.getStr("fileName"));
        assertEquals(20L * 1024 * 1024, content.getLong("fileSize"));
        assertEquals("jpg", content.getStr("ext"));
        assertSame(first, biz.sendMsg(imageRequest("image_retry")));
        verify(messages, times(1)).save(any());
        verify(mapper, times(1)).updateUnreadByConvId(42L, "sender-1");
    }

    @Test
    void safeImageTypesAreAccepted() {
        for (String ext : List.of("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")) {
            when(files.getById("image-1")).thenReturn(imageFile(ext, "image/" + ext, 128L));
            assertEquals(ext, JSONUtil.parseObj(biz.sendMsg(imageRequest(null)).getContent()).getStr("ext"));
        }
    }

    @Test
    void unsafeImageTypesAndMimeAreRejectedBeforeInsert() {
        for (String[] type : List.of(new String[]{"svg", "image/svg+xml"}, new String[]{"html", "image/png"},
            new String[]{"jpg", "application/octet-stream"}, new String[]{"png", "image/svg+xml"},
            new String[]{"jpg", ""})) {
            when(files.getById("image-1")).thenReturn(imageFile(type[0], type[1], 128L));
            assertThrows(BuzzException.class, () -> biz.sendMsg(imageRequest(null)));
        }
        verify(messages, never()).save(any());
        verify(mapper, never()).updateUnreadByConvId(any(), any());
    }

    @Test
    void missingDeletedOrOtherUsersImageIsRejected() {
        assertThrows(BuzzException.class, () -> biz.sendMsg(imageRequest(null)));
        FileSave file = imageFile("jpg", "image/jpeg", 128L);
        file.setDeleted(true);
        when(files.getById("image-1")).thenReturn(file);
        assertThrows(BuzzException.class, () -> biz.sendMsg(imageRequest(null)));
        file.setDeleted(false);
        file.setCrtUser("another-user");
        assertThrows(BuzzException.class, () -> biz.sendMsg(imageRequest(null)));
        file.setCrtUser(null);
        assertThrows(BuzzException.class, () -> biz.sendMsg(imageRequest(null)));
        verify(messages, never()).save(any());
    }

    @Test
    void emptyNegativeMissingAndOversizeImagesAreRejected() {
        for (Long size : new Long[]{null, -1L, 0L, 20L * 1024 * 1024 + 1}) {
            when(files.getById("image-1")).thenReturn(imageFile("jpg", "image/jpeg", size));
            assertThrows(BuzzException.class, () -> biz.sendMsg(imageRequest(null)));
        }
        verify(messages, never()).save(any());
    }

    @Test
    void imagePermissionIsCheckedBeforeReadingAttachment() {
        doThrow(new BuzzException("无权访问该会话")).when(participants).requireParticipant(42L, "sender-1");
        assertThrows(BuzzException.class, () -> biz.sendMsg(imageRequest(null)));
        verifyNoInteractions(files, messages, mapper);
    }

    private FileSave imageFile(String ext, String mime, Long size) {
        FileSave file = new FileSave();
        file.setId("image-1");
        file.setCrtUser("sender-1");
        file.setDeleted(false);
        file.setExt(ext);
        file.setContentType(mime);
        file.setSize(size);
        file.setOriginalFilename("真实图片.jpg");
        return file;
    }

    private ImConversationSendMsgReqVo imageRequest(String key) {
        ImConversationSendMsgReqVo req = request(key, "{\"fileId\":\"image-1\"}");
        req.setType(ImMessageTypeEnum.IMAGE);
        return req;
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
