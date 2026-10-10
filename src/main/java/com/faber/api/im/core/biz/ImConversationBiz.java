package com.faber.api.im.core.biz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.faber.api.base.admin.biz.FileSaveBiz;
import com.faber.api.base.admin.biz.UserBiz;
import com.faber.api.base.admin.entity.FileSave;
import com.faber.api.base.admin.entity.User;
import com.faber.api.base.tn.biz.TenantUserBiz;
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
import com.faber.api.im.core.vo.req.ImConversationUpdatePinnedReqVo;
import com.faber.api.im.core.vo.req.ImConversationUpdateMutedReqVo;
import com.faber.api.im.core.vo.ret.ImConversationRetVo;
import com.faber.config.websocket.WsHolder;
import com.faber.core.context.BaseContextHandler;
import com.faber.core.context.TenantContext;
import com.faber.core.constant.FaSetting;
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
    @Resource TenantUserBiz tenantUserBiz;
    @Resource FaSetting faSetting;

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
        List<String> userIds = normalizeSingleUserIds(getCurrentUserId(), reqVo.getToUserId());
        if (userIds.get(0).equals(userIds.get(1))) {
            throw new BuzzException("不能与自己发起单聊");
        }
        requireExistingUsers(userIds);
        String tenantId = TenantContext.getTenantId();
        String singleKey = (StrUtil.isBlank(tenantId) ? "g" : "t:" + tenantId)
            + ":" + String.join(",", userIds);
        JSONArray userIdArray = new JSONArray(userIds);
        String userIdsStr = userIdArray.toString();

        ImConversation existing = findSingleByKey(singleKey);
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
            return findSingleByKey(singleKey);
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

    static List<String> normalizeSingleUserIds(String currentUserId, String toUserId) {
        List<String> userIds = new ArrayList<>(Arrays.asList(currentUserId, toUserId));
        Collections.sort(userIds);
        return userIds;
    }

    ImConversation findSingleByKey(String singleKey) {
        return lambdaQuery()
            .eq(ImConversation::getSingleKey, singleKey)
            .one();
    }

    /** 聊天封面图片，为参加聊天的用户头像数组 */
    JSONArray getUserImgs(List<String> userIds) {
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
        if (reqVo.getSourceConversationId() != null) {
            Long sourceId = reqVo.getSourceConversationId();
            if (sourceId <= 0) throw new BuzzException("源会话ID必须为正数");
            imParticipantBiz.requireParticipant(sourceId, currentUserId);
            ImConversation source = getById(sourceId);
            if (source == null || source.getType() != ImConversationTypeEnum.SINGLE) {
                throw new BuzzException("源会话必须为单聊");
            }
            List<String> sourceUserIds = getParticipantUserIds(sourceId);
            if (sourceUserIds.size() != 2 || !sourceUserIds.contains(currentUserId)) {
                throw new BuzzException("源单聊成员数据不完整");
            }
            LinkedHashSet<String> members = new LinkedHashSet<>(sourceUserIds);
            members.addAll(userIds);
            userIds = new ArrayList<>(members);
        }
        if (!userIds.contains(currentUserId)) {
            userIds.add(0, currentUserId);
        }
        if (userIds.size() < 3) {
            throw new BuzzException("群聊最少添加三位用户");
        }
        if (userIds.size() > 100) {
            throw new BuzzException("群聊最多添加一百位用户");
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
        if (baseMapper.lockForSend(conversationId) == null) {
            throw new BuzzException("群聊不存在");
        }
        imParticipantBiz.requireParticipantForUpdate(conversationId, getCurrentUserId());
        List<String> requestedUserIds = normalizeUserIds(reqVo.getUserIds());
        requireExistingUsers(requestedUserIds);

        // 过滤已经参加该群聊的用户
        List<String> inUserIdList = imParticipantBiz.lambdaQuery()
            .eq(ImParticipant::getConversationId, conversationId)
            .select(ImParticipant::getUserId)
            .orderByAsc(ImParticipant::getCrtTime, ImParticipant::getUserId)
            .last("FOR UPDATE")
            .list()
            .stream().map(ImParticipant::getUserId).toList();
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

        List<String> participantUserIds = new ArrayList<>(inUserIdList);
        participantUserIds.addAll(addUserIds);
        // 持锁后以当前成员记录构造封面和成员缓存，避免并发邀请使用旧快照。
        JSONArray imgArr = getUserImgs(participantUserIds.stream().limit(9).toList());
        conversation.setCover(imgArr.toString());
        conversation.setUserIds(new JSONArray(participantUserIds).toString());
        this.lambdaUpdate()
            .eq(ImConversation::getId, conversation.getId())
            .set(ImConversation::getCover, conversation.getCover())
            .set(ImConversation::getUserIds, conversation.getUserIds())
            .update();

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
        if (faSetting.isTenantEnabled() && TenantContext.isSuperAdminWithoutTenant()) {
            return Collections.emptyList();
        }
        requireTenantScope();
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

        String clientMessageId = reqVo.getClientMessageId();
        if (clientMessageId != null && !clientMessageId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BuzzException("客户端消息标识格式无效");
        }
        // 串行化同一会话的发送，避免两个请求同时检查不存在后插入。
        // 不捕获唯一冲突继续使用事务，兼容 PostgreSQL 的事务失败语义。
        if (baseMapper.lockForSend(reqVo.getConversationId()) == null) {
            throw new BuzzException("会话不存在或已被删除");
        }

        // create new message
        ImMessage msg = new ImMessage();
        msg.setConversationId(reqVo.getConversationId());
        msg.setSenderId(getCurrentUserId());
        msg.setType(reqVo.getType());
        msg.setContent(normalizeMessageContent(reqVo, msg));
        msg.setTenantId(TenantContext.getTenantId());
        msg.setIsWithdrawn(false);
        msg.setClientMessageId(clientMessageId);
        if (clientMessageId != null) {
            ImMessage existing = imMessageBiz.findClientMessage(reqVo.getConversationId(), getCurrentUserId(), clientMessageId);
            if (existing != null) {
                if (existing.getType() != msg.getType() || !msg.getContent().equals(existing.getContent())) {
                    throw new BuzzException("客户端消息标识已用于其他消息");
                }
                existing.setSenderUserImg(userBiz.getLoginUser().getImg());
                return existing;
            }
        }
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
            case VOICE:
                lastMsg = BaseContextHandler.getName() + ":语音";
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

        // 会话锁后采用当前读，避免旧快照遗漏已提交的个人免打扰设置。
        List<ImParticipant> convList = imParticipantBiz.lambdaQuery()
            .eq(ImParticipant::getConversationId, reqVo.getConversationId())
            .last("FOR UPDATE")
            .list();
        // 所有接收者仍获得 IM 数据事件；免打扰仅关闭提示，不丢失同步消息。
        msg.setSenderUserImg(userBiz.getLoginUser().getImg());
        for (boolean notificationEnabled : new boolean[]{true, false}) {
            List<String> userIds = convList.stream()
                .filter(participant -> !getCurrentUserId().equals(participant.getUserId()))
                .filter(participant -> Boolean.TRUE.equals(participant.getMuted()) != notificationEnabled)
                .map(ImParticipant::getUserId).distinct().toList();
            if (userIds.isEmpty()) continue;
            ImMessage event = new ImMessage();
            BeanUtils.copyProperties(msg, event);
            event.setNotificationEnabled(notificationEnabled);
            sendAfterCommit(userIds, WsTypeEnum.IM, event);
        }

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

        if (reqVo.getType() == ImMessageTypeEnum.IMAGE) {
            validateImageAttachment(fileSave, ext);
        }

        JSONObject normalizedContent = new JSONObject();
        normalizedContent.set("fileId", fileId);
        normalizedContent.set("fileName", fileSave.getOriginalFilename());
        normalizedContent.set("fileSize", fileSave.getSize());
        normalizedContent.set("ext", ext.toLowerCase(Locale.ROOT));
        if (reqVo.getType() == ImMessageTypeEnum.VOICE) {
            Integer duration;
            try { duration = Integer.valueOf(String.valueOf(requestContent.get("duration"))); }
            catch (Exception e) { throw new BuzzException("语音时长无效"); }
            if (duration == null || duration < 1 || duration > 60) throw new BuzzException("语音时长须为1至60秒");
            if (Boolean.TRUE.equals(fileSave.getDeleted()) || !getCurrentUserId().equals(fileSave.getCrtUser())) {
                throw new BuzzException("只能发送自己上传的语音");
            }
            if (!List.of("aac", "m4a", "mp3", "wav").contains(ext.toLowerCase(Locale.ROOT))
                    || fileSave.getSize() <= 0 || fileSave.getSize() > 10L * 1024 * 1024) {
                throw new BuzzException("语音格式不支持或超过10MB");
            }
            normalizedContent.set("duration", duration);
        }
        msg.setFileId(fileId);
        return normalizedContent.toString();
    }

    /** 图片只接受当前用户上传的安全图片，限制与移动端一致（20 MiB）。 */
    private void validateImageAttachment(FileSave fileSave, String ext) {
        if (Boolean.TRUE.equals(fileSave.getDeleted()) || !getCurrentUserId().equals(fileSave.getCrtUser())) {
            throw new BuzzException("只能发送自己上传且未删除的图片");
        }
        String normalizedExt = ext.toLowerCase(Locale.ROOT);
        String contentType = StrUtil.trim(StrUtil.nullToEmpty(fileSave.getContentType())).toLowerCase(Locale.ROOT);
        if (!List.of("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp").contains(normalizedExt)
            || !contentType.startsWith("image/") || contentType.startsWith("image/svg")) {
            throw new BuzzException("附件不是支持的图片格式");
        }
        if (fileSave.getSize() <= 0 || fileSave.getSize() > 20L * 1024 * 1024) {
            throw new BuzzException("图片大小必须大于0且不超过20MB");
        }
    }

    /** 兼容旧调用方，推进到锁定会话后的最新消息。 */
    @Transactional
    public void updateConversationRead(String userId, Long conversationId) {
        updateConversationRead(userId, conversationId, null);
    }

    /** 仅标记客户端展示过的消息；与发送共用会话行锁，避免并发发送被清零。 */
    @Transactional
    public void updateConversationRead(String userId, Long conversationId, Long lastReadMessageId) {
        requireTenantScope();
        if (conversationId == null || conversationId <= 0 || userId == null) {
            throw new BuzzException("会话参数无效");
        }
        if (lastReadMessageId != null && lastReadMessageId <= 0) {
            throw new BuzzException("消息ID必须为正数");
        }
        imParticipantBiz.requireParticipant(conversationId, userId);
        if (baseMapper.lockForSend(conversationId) == null) {
            throw new BuzzException("会话不存在或已被删除");
        }
        ImParticipant participant = imParticipantBiz.requireParticipantForUpdate(conversationId, userId);
        var query = imMessageBiz.lambdaQuery().eq(ImMessage::getConversationId, conversationId);
        if (lastReadMessageId == null) {
            query.orderByDesc(ImMessage::getId).last("LIMIT 1 FOR UPDATE");
        } else {
            query.eq(ImMessage::getId, lastReadMessageId).last("FOR UPDATE");
        }
        ImMessage readMessage = query.one();
        if (lastReadMessageId != null && readMessage == null) {
            throw new BuzzException("已读消息不存在或不属于该会话");
        }
        Long previousId = participant.getLastReadMessageId();
        Long cursor = readMessage == null ? previousId : readMessage.getId();
        if (previousId != null && (cursor == null || previousId > cursor)) cursor = previousId;
        Long unreadCount = imMessageBiz.countUnreadForUpdate(conversationId, userId, cursor == null ? 0L : cursor);
        imParticipantBiz.lambdaUpdate()
            .eq(ImParticipant::getConversationId, conversationId)
            .eq(ImParticipant::getUserId, userId)
            .set(ImParticipant::getUnreadCount, Math.toIntExact(unreadCount))
            .set(ImParticipant::getLastReadMessageId, cursor)
            .update();
    }

    /** 个人置顶只修改当前用户的参与记录，不影响其他成员。 */
    @Transactional
    public void updateConversationPinned(ImConversationUpdatePinnedReqVo reqVo) {
        requireTenantScope();
        String userId = getCurrentUserId();
        if (reqVo == null || reqVo.getConversationId() == null || reqVo.getConversationId() <= 0
                || reqVo.getPinned() == null || StrUtil.isBlank(userId)) {
            throw new BuzzException("会话置顶参数无效");
        }
        Long conversationId = reqVo.getConversationId();
        imParticipantBiz.requireParticipant(conversationId, userId);
        if (baseMapper.lockForSend(conversationId) == null) {
            throw new BuzzException("会话不存在或已被删除");
        }
        ImParticipant participant = imParticipantBiz.requireParticipantForUpdate(conversationId, userId);
        boolean pinned = reqVo.getPinned();
        // 重复开启不改变排序时间，重复关闭也不产生额外写入。
        if (pinned == Boolean.TRUE.equals(participant.getPinned())
                && (pinned ? participant.getPinnedTime() != null : participant.getPinnedTime() == null)) return;
        Date pinnedTime = pinned ? new Date() : null;
        imParticipantBiz.lambdaUpdate()
            .eq(ImParticipant::getId, participant.getId())
            .eq(ImParticipant::getConversationId, conversationId)
            .eq(ImParticipant::getUserId, userId)
            .set(ImParticipant::getPinned, pinned)
            .set(ImParticipant::getPinnedTime, pinnedTime)
            .update();
    }

    /** 个人免打扰只修改当前用户参与记录，保留会话未读数和已读游标。 */
    @Transactional
    public void updateConversationMuted(ImConversationUpdateMutedReqVo reqVo) {
        requireTenantScope();
        String userId = getCurrentUserId();
        if (reqVo == null || reqVo.getConversationId() == null || reqVo.getConversationId() <= 0
                || reqVo.getMuted() == null || StrUtil.isBlank(userId)) {
            throw new BuzzException("消息免打扰参数无效");
        }
        Long conversationId = reqVo.getConversationId();
        imParticipantBiz.requireParticipant(conversationId, userId);
        if (baseMapper.lockForSend(conversationId) == null) {
            throw new BuzzException("会话不存在或已被删除");
        }
        ImParticipant participant = imParticipantBiz.requireParticipantForUpdate(conversationId, userId);
        if (reqVo.getMuted() == Boolean.TRUE.equals(participant.getMuted())) return;
        imParticipantBiz.lambdaUpdate()
            .eq(ImParticipant::getId, participant.getId())
            .eq(ImParticipant::getConversationId, conversationId)
            .eq(ImParticipant::getUserId, userId)
            .set(ImParticipant::getMuted, reqVo.getMuted())
            .update();
    }

    public Integer getUnreadCount() {
        if (faSetting.isTenantEnabled() && TenantContext.isSuperAdminWithoutTenant()) {
            return 0;
        }
        requireTenantScope();
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
        requireTenantScope();
        long existingUserCount = userBiz.lambdaQuery()
            .in(User::getId, userIds)
            .eq(User::getStatus, true)
            .count();
        if (existingUserCount != userIds.size()) {
            throw new BuzzException("会话包含不存在或不可用的用户");
        }
        if (!faSetting.isTenantEnabled()) return;

        String tenantId = TenantContext.requireTenantId();
        if (!tenantUserBiz.getUserIdsByTenantId(tenantId).containsAll(userIds)) {
            throw new BuzzException("会话成员必须属于当前租户");
        }
    }

    private void requireTenantScope() {
        if (faSetting.isTenantEnabled()) TenantContext.requireTenantId();
    }
}
