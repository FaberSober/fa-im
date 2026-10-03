package com.faber.api.im.core.biz;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.mapper.ImMessageMapper;
import com.faber.api.im.core.vo.req.ImMessagePageQueryVo;
import com.faber.api.im.core.vo.req.ImMessageListAfterReqVo;
import java.util.List;
import com.faber.core.exception.BuzzException;
import com.faber.core.vo.msg.TableRet;
import com.faber.core.vo.query.BasePageQuery;
import com.faber.core.web.biz.BaseBiz;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;

/**
 * IM-消息表
 *
 * @author xu.pengfei
 * @email 1508075252@qq.com
 * @date 2025-09-07 21:51:31
 */
@Service
public class ImMessageBiz extends BaseBiz<ImMessageMapper,ImMessage> {

    @Resource
    private ImParticipantBiz imParticipantBiz;

    /** 仅由已完成会话权限校验和行锁的发送事务调用。 */
    public ImMessage findClientMessage(Long conversationId, String senderId, String clientMessageId) {
        return baseMapper.findClientMessage(conversationId, senderId, clientMessageId);
    }

    /** 会话行已锁定后的未读消息当前读。 */
    public Long countUnreadForUpdate(Long conversationId, String userId, Long lastReadMessageId) {
        return baseMapper.countUnreadForUpdate(conversationId, userId, lastReadMessageId);
    }

    public TableRet<ImMessage> pageQuery(BasePageQuery<ImMessagePageQueryVo> query) {
        if (query == null || query.getQuery() == null || query.getQuery().getConversationId() == null) {
            throw new BuzzException("会话ID不能为空");
        }
        if (query.getCurrent() < 1 || query.getPageSize() < 1 || query.getPageSize() > 100) {
            throw new BuzzException("分页参数无效，每页最多查询100条消息");
        }
        if (query.getQuery().getMaxMsgId() != null && query.getQuery().getMaxMsgId() <= 0) {
            throw new BuzzException("消息ID必须为正数");
        }
        imParticipantBiz.requireParticipant(query.getQuery().getConversationId(), getCurrentUserId());

        PageInfo<ImMessage> info = PageHelper.startPage(query.getCurrent(), query.getPageSize())
                .doSelectPageInfo(() -> baseMapper.pageQuery(query.getQuery()));
        return new TableRet<>(info);
    }

    /** 无偏移量的增量补查；不修改已读状态。 */
    public List<ImMessage> listAfter(ImMessageListAfterReqVo query) {
        if (query == null || query.getConversationId() == null || query.getConversationId() <= 0
                || query.getAfterMessageId() == null || query.getAfterMessageId() < 0) {
            throw new BuzzException("会话ID必须为正数，消息游标不能为负数");
        }
        imParticipantBiz.requireParticipant(query.getConversationId(), getCurrentUserId());
        return baseMapper.listAfter(query);
    }
}
