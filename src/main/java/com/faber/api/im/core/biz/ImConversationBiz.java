package com.faber.api.im.core.biz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.faber.api.base.admin.biz.FileSaveBiz;
import com.faber.api.base.admin.biz.UserBiz;
import com.faber.api.base.admin.entity.FileSave;
import com.faber.api.base.admin.entity.User;
import com.faber.api.im.core.entity.ImConversation;
import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.entity.ImParticipant;
import com.faber.api.im.core.enums.ImConversationTypeEnum;
import com.faber.api.im.core.enums.ImMessageTypeEnum;
import com.faber.api.im.core.mapper.ImConversationMapper;
import com.faber.api.im.core.vo.req.ImConversationAddGroupUsersReqVo;
import com.faber.api.im.core.vo.req.ImConversationCreateNewGroupReqVo;
import com.faber.api.im.core.vo.req.ImConversationCreateNewSingleReqVo;
import com.faber.api.im.core.vo.req.ImConversationGetParticipantReqVo;
import com.faber.api.im.core.vo.req.ImConversationListQueryReqVo;
import com.faber.api.im.core.vo.req.ImConversationRemoveGroupUsersReqVo;
import com.faber.api.im.core.vo.req.ImConversationRenameReqVo;
import com.faber.api.im.core.vo.req.ImConversationSendMsgReqVo;
import com.faber.api.im.core.vo.ret.ImConversationRetVo;
import com.faber.config.websocket.WsHolder;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.enums.WsTypeEnum;
import com.faber.core.exception.BuzzException;
import com.faber.core.vo.msg.TableRet;
import com.faber.core.vo.query.BasePageQuery;
import com.faber.core.web.biz.BaseBiz;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;

/**
 * IM-会话表
 *
 * @author xu.pengfei
 * @email 1508075252@qq.com
 * @date 2025-09-07 21:51:31
 */
@Service
public class ImConversationBiz extends BaseBiz<ImConversationMapper,ImConversation> {

    @Resource UserBiz userBiz;
    @Resource FileSaveBiz fileSaveBiz;
    @Resource ImParticipantBiz imParticipantBiz;
    @Resource ImMessageBiz imMessageBiz;

    /**
     * 创建新的单聊会话
     * 1. 根据单聊对方用户ID查询是否已经存在聊天，如果存在，则直接返回；
     * 2. 如果不存在，则创建新的聊天，然后返回；
     * @param reqVo
     * @return
     */
    @Transactional
    public ImConversation createNewSingle(ImConversationCreateNewSingleReqVo reqVo) {
        // 将参考单聊的用户IDs进行排序，然后转换为jsonarray
        List<String> userIds = Arrays.asList(getCurrentUserId(), reqVo.getToUserId());
        Collections.sort(userIds);
        String singleKey = String.join(",", userIds);
        JSONArray userIdArray = new JSONArray(userIds);
        String userIdsStr = userIdArray.toString();

        LambdaQueryChainWrapper<ImConversation> wrapper = lambdaQuery()
            .eq(ImConversation::getSingleKey, singleKey);
        ImConversation existing = wrapper.one();
        if (existing != null) {
            return existing;
        }

        User toUser = userBiz.getById(reqVo.getToUserId());

        // 聊天封面图片，为参加聊天的用户头像数组
        JSONArray imgArr = getUserImgs(Arrays.asList(getCurrentUserId(), reqVo.getToUserId()));

        // create new conversation
        ImConversation conversation = new ImConversation();
        conversation.setUserIds(userIdsStr);
        conversation.setSingleKey(singleKey);
        conversation.setType(ImConversationTypeEnum.SINGLE);
        conversation.setTitle("单聊");
        conversation.setCover(imgArr.toString());
        try {
            this.save(conversation);
        } catch (DuplicateKeyException e) {
            return lambdaQuery()
                .eq(ImConversation::getSingleKey, singleKey)
                .one();
        }

        // save conversation user link
        {
            ImParticipant participantCrt = new ImParticipant();
            participantCrt.setConversationId(conversation.getId());
            participantCrt.setUserId(getCurrentUserId());
            participantCrt.setTitle(toUser.getName()); // 存对方的名称
            participantCrt.setUnreadCount(0);
            imParticipantBiz.save(participantCrt);
        }
        {
            ImParticipant participantTo = new ImParticipant();
            participantTo.setConversationId(conversation.getId());
            participantTo.setUserId(reqVo.getToUserId());
            participantTo.setTitle(BaseContextHandler.getName()); // 存对方的名称
            participantTo.setUnreadCount(0);
            imParticipantBiz.save(participantTo);
        }

        return conversation;
    }

    /** 聊天封面图片，为参加聊天的用户头像数组 */
    private JSONArray getUserImgs(List<String> userIds) {
        JSONArray imgArr = new JSONArray();
        List<User> userList = userBiz.lambdaQuery()
            .in(User::getId, userIds)
            .orderByAsc(User::getId)
            .select(User::getId, User::getImg, User::getName)
            .list();
        for (User user : userList) {
            JSONObject userJson = new JSONObject();
            userJson.set("id", user.getId());
            userJson.set("img", user.getImg());
            userJson.set("name", user.getName());
            imgArr.add(userJson);
        }
        return imgArr;
    }

    /** 创建新的群聊 */
    @Transactional
    public ImConversation createNewGroup(ImConversationCreateNewGroupReqVo reqVo) {
        List<String> userIds = normalizeUserIds(reqVo.getUserIds());
        String currentUserId = getCurrentUserId();
        if (!userIds.contains(currentUserId)) {
            userIds.add(0, currentUserId);
        }
        if (userIds.size() < 3) {
            throw new BuzzException("群聊最少添加三位用户");
        }
        requireExistingUsers(userIds);

        // 聊天封面图片，为参加聊天的用户头像数组
        JSONArray imgArr = getUserImgs(userIds);

        String title = BaseContextHandler.getName() + "发起的群聊";

        // create new conversation
        ImConversation conversation = new ImConversation();
        conversation.setUserIds(new JSONArray(userIds).toString());
        conversation.setType(ImConversationTypeEnum.GROUP);
        conversation.setTitle(title);
        conversation.setCover(imgArr.toString());
        conversation.setManagerId(getCurrentUserId()); // 管理员为创建人
        this.save(conversation);

        // save conversation user link
        List<ImParticipant> participantList = new ArrayList<>();
        for (String userId : userIds) {
            ImParticipant participant = new ImParticipant();
            participant.setConversationId(conversation.getId());
            participant.setUserId(userId);
            participant.setTitle(""); // 存群聊名称
            participant.setUnreadCount(0);
            participantList.add(participant);
        }
        imParticipantBiz.saveBatch(participantList);
        sendAfterCommit(
            userIds.stream().filter(userId -> !userId.equals(getCurrentUserId())).toList(),
            WsTypeEnum.IM_REFRESH_GROUP_CHAT,
            conversation
        );

        return conversation;
    }

    /** 创建新的群聊 */
    @Transactional
    public ImConversation addGroupUsers(ImConversationAddGroupUsersReqVo reqVo) {
        Long conversationId = reqVo.getConversationId();
        ImConversation conversation = requireGroupParticipant(conversationId, getCurrentUserId());
        List<String> requestedUserIds = normalizeUserIds(reqVo.getUserIds());
        requireExistingUsers(requestedUserIds);

        // 过滤已经参加该群聊的用户
        List<String> inUserIdList = imParticipantBiz.lambdaQuery()
            .eq(ImParticipant::getConversationId, conversationId)
            .in(ImParticipant::getUserId, requestedUserIds)
            .select(ImParticipant::getUserId)
            .list()
            .stream().map(i -> i.getUserId()).toList();
        List<String> addUserIds = requestedUserIds.stream()
            .filter(i -> !inUserIdList.contains(i))
            .toList();
        if (addUserIds == null || addUserIds.isEmpty()) {
            return conversation;
        }

        // save conversation user link
        List<ImParticipant> participantList = new ArrayList<>();
        for (String userId : addUserIds) {
            ImParticipant participant = new ImParticipant();
            participant.setConversationId(conversation.getId());
            participant.setUserId(userId);
            participant.setTitle(conversation.getTitle()); // 存群聊名称
            participant.setUnreadCount(0);
            participantList.add(participant);
        }
        imParticipantBiz.saveBatch(participantList);

        // update conversation cover
        List<String> coverUserIds = imParticipantBiz.lambdaQuery()
            .eq(ImParticipant::getConversationId, conversationId)
            .select(ImParticipant::getUserId)
            .orderByAsc(ImParticipant::getCrtTime, ImParticipant::getUserId)
            .last("limit 9") // 群聊封面最多展示9个用户头像
            .list()
            .stream().map(i -> i.getUserId()).toList();

        // 更新群聊头像
        JSONArray imgArr = getUserImgs(coverUserIds);
        conversation.setCover(imgArr.toString());

        this.lambdaUpdate()
            .eq(ImConversation::getId, conversation.getId())
            .set(ImConversation::getCover, imgArr.toString())
            .update();

        List<String> participantUserIds = getParticipantUserIds(conversationId);
        conversation.setUserIds(new JSONArray(participantUserIds).toString());
        sendAfterCommit(participantUserIds, WsTypeEnum.IM_REFRESH_GROUP_CHAT, conversation);

        return conversation;
    }

    /** 移出群聊 */
    @Transactional
    public ImConversation removeGroupUsers(ImConversationRemoveGroupUsersReqVo reqVo) {
        Long conversationId = reqVo.getConversationId();
        ImConversation conversation = requireGroupManager(conversationId);
        List<String> requestedUserIds = normalizeUserIds(reqVo.getUserIds());

        List<String> removeUserIds = imParticipantBiz.lambdaQuery()
            .eq(ImParticipant::getConversationId, conversationId)
            .in(ImParticipant::getUserId, requestedUserIds)
            .select(ImParticipant::getUserId)
            .list()
            .stream().map(ImParticipant::getUserId).distinct().toList();
        if (removeUserIds.isEmpty()) {
            throw new BuzzException("移出用户不是群聊成员");
        }
        if (removeUserIds.contains(conversation.getManagerId())) {
            throw new BuzzException("不能移出群管理员");
        }

        return removeGroupUsers(conversation, removeUserIds);
    }

    /** 当前用户退出群聊。 */
    @Transactional
    public ImConversation exitGroupChat(Long conversationId) {
        ImConversation conversation = requireGroupParticipant(conversationId, getCurrentUserId());
        if (getCurrentUserId().equals(conversation.getManagerId())) {
            throw new BuzzException("群管理员需先转移管理员后才能退出群聊");
        }
        return removeGroupUsers(conversation, List.of(getCurrentUserId()));
    }

    private ImConversation removeGroupUsers(ImConversation conversation, List<String> userIds) {
        Long conversationId = conversation.getId();

        // 移出群聊
        imParticipantBiz.lambdaUpdate()
            .eq(ImParticipant::getConversationId, conversationId)
            .in(ImParticipant::getUserId, userIds)
            .remove();

        // update conversation cover
        List<String> coverUserIds = imParticipantBiz.lambdaQuery()
            .eq(ImParticipant::getConversationId, conversationId)
            .select(ImParticipant::getUserId)
            .orderByAsc(ImParticipant::getCrtTime, ImParticipant::getUserId)
            .last("limit 9") // 群聊封面最多展示9个用户头像
            .list()
            .stream().map(i -> i.getUserId()).toList();

        // 更新群聊头像
        JSONArray imgArr = getUserImgs(coverUserIds);
        conversation.setCover(imgArr.toString());

        this.lambdaUpdate()
            .eq(ImConversation::getId, conversation.getId())
            .set(ImConversation::getCover, imgArr.toString())
            .update();

        List<String> participantUserIds = getParticipantUserIds(conversationId);
        conversation.setUserIds(new JSONArray(participantUserIds).toString());
        sendAfterCommit(userIds, WsTypeEnum.IM_EXIT_GROUP_CHAT, conversation);
        sendAfterCommit(participantUserIds, WsTypeEnum.IM_REFRESH_GROUP_CHAT, conversation);

        return conversation;
    }

    @Transactional
    public ImConversation renameGroup(ImConversationRenameReqVo reqVo) {
        Long conversationId = reqVo.getConversationId();
        ImConversation conversation = requireGroupManager(conversationId);
        lambdaUpdate()
            .eq(ImConversation::getId, conversationId)
            .set(ImConversation::getTitle, reqVo.getTitle())
            .update();
        conversation.setTitle(reqVo.getTitle());
        sendAfterCommit(getParticipantUserIds(conversationId), WsTypeEnum.IM_REFRESH_GROUP_CHAT, conversation);
        return conversation;
    }

    /**
     * 查询聊天记录
     * 
     * @param reqVo
     * @return
     */
    public List<ImConversationRetVo> listQuery(ImConversationListQueryReqVo reqVo) {
        // 查询用户参加的聊天记录
        List<ImConversationRetVo> convList = baseMapper.listQuery(getCurrentUserId(), reqVo);
        return convList;
    }

    /**
     * 发送消息
     * @param reqVo
     * @return
     */
    @Transactional
    public ImMessage sendMsg(ImConversationSendMsgReqVo reqVo) {
        if (reqVo == null || reqVo.getType() == null) {
            throw new BuzzException("消息类型不能为空");
        }
        imParticipantBiz.requireParticipant(reqVo.getConversationId(), getCurrentUserId());

        // create new message
        ImMessage msg = new ImMessage();
        msg.setConversationId(reqVo.getConversationId());
        msg.setSenderId(getCurrentUserId());
        msg.setType(reqVo.getType());
        msg.setContent(normalizeMessageContent(reqVo, msg));
        msg.setIsWithdrawn(false);
        imMessageBiz.save(msg);

        // update conversation last message，超过250个字符截断
        String lastMsg = BaseContextHandler.getName() + ":" + reqVo.getContent();
        // 如果lastMsg超过250个字符，则截断
        if (lastMsg.length() > 250) {
            lastMsg = lastMsg.substring(0, 250);
        }
        // 文件类型
        switch (reqVo.getType()) {
            case IMAGE:
                lastMsg = BaseContextHandler.getName() + ":" + "图片";
                break;
            case VIDEO:
                lastMsg = BaseContextHandler.getName() + ":" + "视频";
                break;
            case FILE:
                lastMsg = BaseContextHandler.getName() + ":" + "文件";
                break;
            default:
                break;
        }
        this.lambdaUpdate()
            .eq(ImConversation::getId, reqVo.getConversationId())
            .set(ImConversation::getLastMsg, lastMsg)
            .update();

        // update unread count
        baseMapper.updateUnreadByConvId(reqVo.getConversationId(), getCurrentUserId());

        // get conversation participants
        List<ImParticipant> convList = imParticipantBiz.lambdaQuery()
            .eq(ImParticipant::getConversationId, reqVo.getConversationId())
            .list();
        // get userIds except senderId
        List<String> userIds = convList.stream().map(ImParticipant::getUserId).filter(userId -> !userId.equals(getCurrentUserId())).toList();

        // send message throw websocket
        msg.setSenderUserImg(userBiz.getLoginUser().getImg());
        sendAfterCommit(userIds, WsTypeEnum.IM, msg);

        return msg;
    }

    private List<String> getParticipantUserIds(Long conversationId) {
        return imParticipantBiz.lambdaQuery()
            .eq(ImParticipant::getConversationId, conversationId)
            .select(ImParticipant::getUserId)
            .orderByAsc(ImParticipant::getCrtTime, ImParticipant::getUserId)
            .list()
            .stream().map(ImParticipant::getUserId).toList();
    }

    /** 事务提交后再通知，避免客户端收到尚未可查询的数据。 */
    private void sendAfterCommit(List<String> userIds, WsTypeEnum type, Object message) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            WsHolder.sendMessage(userIds, type, message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                WsHolder.sendMessage(userIds, type, message);
            }
        });
    }

    /** 按消息类型校验并规范化消息内容。附件元数据以附件表为准，避免客户端伪造。 */
    private String normalizeMessageContent(ImConversationSendMsgReqVo reqVo, ImMessage msg) {
        if (StrUtil.isBlank(reqVo.getContent())) {
            throw new BuzzException("消息内容不能为空");
        }
        if (reqVo.getType() == ImMessageTypeEnum.TEXT) {
            return reqVo.getContent();
        }

        JSONObject requestContent;
        try {
            requestContent = JSONUtil.parseObj(reqVo.getContent());
        } catch (Exception e) {
            throw new BuzzException("文件消息内容格式错误");
        }
        if (requestContent == null) {
            throw new BuzzException("文件消息内容格式错误");
        }

        String fileId = StrUtil.trim(requestContent.getStr("fileId"));
        if (StrUtil.isBlank(fileId) || fileId.length() > 32) {
            throw new BuzzException("文件ID不能为空且长度不能超过32位");
        }

        FileSave fileSave = fileSaveBiz.getById(fileId);
        if (fileSave == null) {
            throw new BuzzException("附件不存在或已被删除");
        }
        if (StrUtil.isBlank(fileSave.getOriginalFilename()) || fileSave.getSize() == null || fileSave.getSize() < 0) {
            throw new BuzzException("附件元数据无效");
        }

        String ext = StrUtil.trim(StrUtil.nullToEmpty(fileSave.getExt()));
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }

        JSONObject normalizedContent = new JSONObject();
        normalizedContent.set("fileId", fileId);
        normalizedContent.set("fileName", fileSave.getOriginalFilename());
        normalizedContent.set("fileSize", fileSave.getSize());
        normalizedContent.set("ext", ext.toLowerCase(Locale.ROOT));
        msg.setFileId(fileId);
        return normalizedContent.toString();
    }

    /**
     * 更新聊天已读
     * @param reqVo
     */
    public void updateConversationRead(String userId, Long conversationId) {
        imParticipantBiz.requireParticipant(conversationId, userId);

        // 查询最新的消息
        ImMessage lastMsg = imMessageBiz.lambdaQuery()
            .eq(ImMessage::getConversationId, conversationId)
            .orderByDesc(ImMessage::getId)
            .last("limit 1")
            .one();
        Long lastReadMessageId = lastMsg == null ? null : lastMsg.getId();

        // 更新用户关联的聊天记录已读数量为0
        imParticipantBiz.lambdaUpdate()
            .eq(ImParticipant::getConversationId, conversationId)
            .eq(ImParticipant::getUserId, userId)
            .set(ImParticipant::getUnreadCount, 0)
            .set(ImParticipant::getLastReadMessageId, lastReadMessageId)
            .update();
    }

    public Integer getUnreadCount() {
        return baseMapper.countUnreadByUserId(getCurrentUserId());
    }

    public TableRet<ImParticipant> getParticipant(BasePageQuery<ImConversationGetParticipantReqVo> query) {
        if (query == null || query.getQuery() == null || query.getQuery().getConversationId() == null) {
            throw new BuzzException("会话ID不能为空");
        }
        Long conversationId = query.getQuery().getConversationId();
        imParticipantBiz.requireParticipant(conversationId, getCurrentUserId());

        PageInfo<ImParticipant> info = PageHelper.startPage(query.getCurrent(), query.getPageSize())
                .doSelectPageInfo(() -> baseMapper.getParticipant(query.getQuery()));
        return new TableRet<>(info);
    }

    private ImConversation requireGroupParticipant(Long conversationId, String userId) {
        if (conversationId == null || conversationId <= 0) {
            throw new BuzzException("会话ID必须为正数");
        }
        ImConversation conversation = getById(conversationId);
        if (conversation == null || conversation.getType() != ImConversationTypeEnum.GROUP) {
            throw new BuzzException("群聊不存在");
        }
        imParticipantBiz.requireParticipant(conversationId, userId);
        return conversation;
    }

    private ImConversation requireGroupManager(Long conversationId) {
        ImConversation conversation = requireGroupParticipant(conversationId, getCurrentUserId());
        if (!getCurrentUserId().equals(conversation.getManagerId())) {
            throw new BuzzException("只有群管理员可以执行此操作");
        }
        return conversation;
    }

    private List<String> normalizeUserIds(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw new BuzzException("群聊用户不能为空");
        }
        return new ArrayList<>(new LinkedHashSet<>(userIds));
    }

    private void requireExistingUsers(List<String> userIds) {
        long existingUserCount = userBiz.lambdaQuery()
            .in(User::getId, userIds)
            .count();
        if (existingUserCount != userIds.size()) {
            throw new BuzzException("群聊包含不存在的用户");
        }
    }
}
