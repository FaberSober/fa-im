package com.faber.api.im.core.biz;

import cn.hutool.json.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.faber.api.base.admin.biz.UserBiz;
import com.faber.api.base.admin.entity.User;
import com.faber.api.base.tn.biz.TenantUserBiz;
import com.faber.api.im.core.entity.ImConversation;
import com.faber.api.im.core.entity.ImParticipant;
import com.faber.api.im.core.enums.ImConversationTypeEnum;
import com.faber.api.im.core.mapper.ImConversationMapper;
import com.faber.api.im.core.vo.req.ImConversationAddGroupUsersReqVo;
import com.faber.api.im.core.vo.req.ImConversationCreateNewGroupReqVo;
import com.faber.config.websocket.WsHolder;
import com.faber.core.constant.FaSetting;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.context.TenantContext;
import com.faber.core.exception.BuzzException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImConversationGroupMembersTest {
    private final ImConversationBiz biz = spy(new ImConversationBiz());
    private final ImParticipantBiz participants = mock(ImParticipantBiz.class);
    private final ImConversationMapper mapper = mock(ImConversationMapper.class);
    private final FaSetting settings = mock(FaSetting.class);
    private final TenantUserBiz tenantUsers = mock(TenantUserBiz.class);
    private final UserBiz users = mock(UserBiz.class);
    private final LambdaQueryChainWrapper<User> userQuery = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
    private final LambdaQueryChainWrapper<ImParticipant> participantQuery = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
    private final LambdaUpdateChainWrapper<ImConversation> update = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);

    @BeforeEach
    void setUp() {
        BaseContextHandler.setUserId("self");
        ReflectionTestUtils.setField(biz, "imParticipantBiz", participants);
        ReflectionTestUtils.setField(biz, "baseMapper", mapper);
        ReflectionTestUtils.setField(biz, "userBiz", users);
        ReflectionTestUtils.setField(biz, "faSetting", settings);
        ReflectionTestUtils.setField(biz, "tenantUserBiz", tenantUsers);
        when(users.lambdaQuery()).thenReturn(userQuery);
        doReturn(userQuery).when(userQuery).in(any(), anyCollection());
        doReturn(userQuery).when(userQuery).eq(any(), any());
        when(userQuery.count()).thenReturn(3L);
        when(participants.lambdaQuery()).thenReturn(participantQuery);
        doReturn(participantQuery).when(participantQuery).eq(any(), any());
        doReturn(participantQuery).when(participantQuery).last(anyString());
        doReturn(participantQuery).when(participantQuery).select(any(SFunction.class));
        doReturn(participantQuery).when(participantQuery).orderByAsc(any(SFunction.class), any(SFunction.class));
        doReturn(new JSONArray()).when(biz).getUserImgs(anyList());
        doAnswer(invocation -> { ((ImConversation) invocation.getArgument(0)).setId(99L); return true; })
            .when(biz).save(any(ImConversation.class));
        doReturn(update).when(biz).lambdaUpdate();
        doReturn(update).when(update).eq(any(), any());
        doReturn(update).when(update).set(any(), any());
        when(mapper.lockForSend(42L)).thenReturn(42L);
    }

    @AfterEach
    void tearDown() { BaseContextHandler.remove(); TenantContext.clear(); }

    @Test
    void singleSourcePreservesBothMembersAndDeduplicatesWithoutChangingSource() {
        ImConversation source = conversation(ImConversationTypeEnum.SINGLE);
        source.setUserIds("[\"forged-cache\"]");
        doReturn(source).when(biz).getById(42L);
        when(participantQuery.list()).thenReturn(List.of(participant("self"), participant("peer")));
        try (var ignored = mockStatic(WsHolder.class)) {
            ImConversation result = biz.createNewGroup(create(42L, List.of("new", "peer", "new")));
            assertEquals(List.of("self", "peer", "new"), new JSONArray(result.getUserIds()).toList(String.class));
            assertEquals(ImConversationTypeEnum.GROUP, result.getType());
            assertEquals(99L, result.getId());
            assertEquals("[\"forged-cache\"]", source.getUserIds());
            verify(participants).requireParticipant(42L, "self");
            verify(biz, never()).updateById(any());
        }
    }

    @Test
    void sourceRejectsNonParticipantBeforeReadingMembersOrCreatingGroup() {
        doThrow(new BuzzException("无权访问该会话")).when(participants).requireParticipant(42L, "self");
        assertThrows(BuzzException.class, () -> biz.createNewGroup(create(42L, List.of("new"))));
        verify(participants, never()).lambdaQuery();
        verify(biz, never()).save(any());
    }

    @Test
    void sourceRejectsExistingGroupAndInvalidId() {
        doReturn(conversation(ImConversationTypeEnum.GROUP)).when(biz).getById(42L);
        assertThrows(BuzzException.class, () -> biz.createNewGroup(create(42L, List.of("new"))));
        assertThrows(BuzzException.class, () -> biz.createNewGroup(create(0L, List.of("new"))));
        verify(biz, never()).save(any());
    }

    @Test
    void sourceRejectsMissingOriginalMemberOrNoAdditionalMember() {
        doReturn(conversation(ImConversationTypeEnum.SINGLE)).when(biz).getById(42L);
        when(participantQuery.list()).thenReturn(List.of(participant("self")));
        assertThrows(BuzzException.class, () -> biz.createNewGroup(create(42L, List.of("new"))));
        when(participantQuery.list()).thenReturn(List.of(participant("self"), participant("peer")));
        assertThrows(BuzzException.class, () -> biz.createNewGroup(create(42L, List.of("peer"))));
        verify(biz, never()).save(any());
    }

    @Test
    void sourceValidatesAvailabilityAndTenantForOriginalAndNewMembers() {
        doReturn(conversation(ImConversationTypeEnum.SINGLE)).when(biz).getById(42L);
        when(participantQuery.list()).thenReturn(List.of(participant("self"), participant("peer")));
        when(userQuery.count()).thenReturn(2L);
        assertThrows(BuzzException.class, () -> biz.createNewGroup(create(42L, List.of("new"))));
        verify(userQuery).in(any(), eq(List.of("self", "peer", "new")));
        when(userQuery.count()).thenReturn(3L);
        when(settings.isTenantEnabled()).thenReturn(true);
        TenantContext.setTenantId("tenant-a");
        when(tenantUsers.getUserIdsByTenantId("tenant-a")).thenReturn(List.of("self", "new"));
        assertThrows(BuzzException.class, () -> biz.createNewGroup(create(42L, List.of("new"))));
        verify(biz, never()).save(any());
    }

    @Test
    void sourceEnforcesMergedMemberLimit() {
        doReturn(conversation(ImConversationTypeEnum.SINGLE)).when(biz).getById(42L);
        when(participantQuery.list()).thenReturn(List.of(participant("self"), participant("peer")));
        List<String> ids = java.util.stream.IntStream.range(0, 99).mapToObj(i -> "new-" + i).toList();
        assertThrows(BuzzException.class, () -> biz.createNewGroup(create(42L, ids)));
        verify(biz, never()).save(any());
    }

    @Test
    void invitationRechecksMembershipAfterLockBeforeSaving() {
        doReturn(conversation(ImConversationTypeEnum.GROUP)).when(biz).getById(42L);
        doThrow(new BuzzException("无权访问该会话")).when(participants).requireParticipantForUpdate(42L, "self");
        assertThrows(BuzzException.class, () -> biz.addGroupUsers(add(List.of("new"))));
        verify(mapper).lockForSend(42L);
        verify(participants, never()).saveBatch(anyCollection());
    }

    @Test
    void ordinaryGroupStillIncludesCreatorAndRequiresThreeUniqueMembers() {
        try (var ignored = mockStatic(WsHolder.class)) {
            ImConversation result = biz.createNewGroup(create(null, List.of("peer", "new")));
            assertEquals(List.of("self", "peer", "new"), new JSONArray(result.getUserIds()).toList(String.class));
            verify(participants, never()).requireParticipant(anyLong(), anyString());
        }
        assertThrows(BuzzException.class, () -> biz.createNewGroup(create(null, List.of("peer", "peer"))));
    }

    @Test
    void ordinaryMemberCanInviteWithLockedCurrentReadAndDeduplicatedMembers() {
        ImConversation group = conversation(ImConversationTypeEnum.GROUP);
        group.setManagerId("other-manager");
        doReturn(group).when(biz).getById(42L);
        when(userQuery.count()).thenReturn(2L);
        when(participantQuery.list()).thenReturn(List.of(participant("self"), participant("peer")));
        try (var ignored = mockStatic(WsHolder.class)) {
            ImConversation result = biz.addGroupUsers(add(List.of("peer", "new", "new")));
            assertEquals(List.of("self", "peer", "new"), new JSONArray(result.getUserIds()).toList(String.class));
            var order = inOrder(participants, mapper, participantQuery);
            order.verify(participants).requireParticipant(42L, "self");
            order.verify(mapper).lockForSend(42L);
            order.verify(participants).requireParticipantForUpdate(42L, "self");
            order.verify(participants).lambdaQuery();
            order.verify(participantQuery).eq(any(), eq(42L));
            order.verify(participantQuery).select(any(SFunction.class));
            order.verify(participantQuery).orderByAsc(any(SFunction.class), any(SFunction.class));
            order.verify(participantQuery).last("FOR UPDATE");
            order.verify(participantQuery).list();
            verify(update).set(any(), eq(result.getUserIds()));
            var added = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
            verify(participants).saveBatch(added.capture());
            assertEquals(List.of("new"), added.getValue().stream().map(p -> ((ImParticipant) p).getUserId()).toList());
        }
    }

    @Test
    void repeatedInviteDoesNotCreateDuplicateParticipantsOrUpdateConversation() {
        doReturn(conversation(ImConversationTypeEnum.GROUP)).when(biz).getById(42L);
        when(userQuery.count()).thenReturn(1L);
        when(participantQuery.list()).thenReturn(List.of(participant("self"), participant("peer")));
        biz.addGroupUsers(add(List.of("peer")));
        verify(participants, never()).saveBatch(anyCollection());
        verify(update, never()).update();
    }

    @Test
    void invitationRejectsNonMemberAndSingleConversation() {
        doReturn(conversation(ImConversationTypeEnum.GROUP)).when(biz).getById(42L);
        doThrow(new BuzzException("无权访问该会话")).when(participants).requireParticipant(42L, "self");
        assertThrows(BuzzException.class, () -> biz.addGroupUsers(add(List.of("new"))));
        verify(mapper, never()).lockForSend(anyLong());
        doReturn(conversation(ImConversationTypeEnum.SINGLE)).when(biz).getById(42L);
        assertThrows(BuzzException.class, () -> biz.addGroupUsers(add(List.of("new"))));
        verify(participants, never()).saveBatch(anyCollection());
    }

    private ImConversation conversation(ImConversationTypeEnum type) {
        ImConversation value = new ImConversation(); value.setId(42L); value.setType(type); return value;
    }
    private ImParticipant participant(String id) {
        ImParticipant value = new ImParticipant(); value.setUserId(id); return value;
    }
    private ImConversationCreateNewGroupReqVo create(Long source, List<String> ids) {
        var req = new ImConversationCreateNewGroupReqVo(); req.setSourceConversationId(source); req.setUserIds(ids); return req;
    }
    private ImConversationAddGroupUsersReqVo add(List<String> ids) {
        var req = new ImConversationAddGroupUsersReqVo(); req.setConversationId(42L); req.setUserIds(ids); return req;
    }
}
