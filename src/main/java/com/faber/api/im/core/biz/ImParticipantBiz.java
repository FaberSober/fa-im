package com.faber.api.im.core.biz;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import com.faber.api.im.core.entity.ImParticipant;
import com.faber.api.im.core.mapper.ImParticipantMapper;
import com.faber.core.constant.FaSetting;
import com.faber.core.context.TenantContext;
import com.faber.core.exception.BuzzException;
import com.faber.core.web.biz.BaseBiz;

/**
 * IM-会话参与者表
 *
 * @author xu.pengfei
 * @email 1508075252@qq.com
 * @date 2025-09-07 21:51:31
 */
@Service
public class ImParticipantBiz extends BaseBiz<ImParticipantMapper,ImParticipant> {

    @Resource
    private FaSetting faSetting;

    /** 校验当前用户是否属于指定会话。 */
    public ImParticipant requireParticipant(Long conversationId, String userId) {
        return requireParticipant(conversationId, userId, false);
    }

    /** 会话行已锁定后的当前读，避免已读事务读取旧快照中的游标。 */
    public ImParticipant requireParticipantForUpdate(Long conversationId, String userId) {
        return requireParticipant(conversationId, userId, true);
    }

    private ImParticipant requireParticipant(Long conversationId, String userId, boolean forUpdate) {
        if (faSetting.isTenantEnabled()) TenantContext.requireTenantId();
        if (conversationId == null || userId == null) {
            throw new BuzzException("会话参数不能为空");
        }
        if (conversationId <= 0) {
            throw new BuzzException("会话ID必须为正数");
        }

        var query = lambdaQuery()
            .eq(ImParticipant::getConversationId, conversationId)
            .eq(ImParticipant::getUserId, userId);
        if (forUpdate) query.last("FOR UPDATE");
        ImParticipant participant = query.one();
        if (participant == null) {
            throw new BuzzException("无权访问该会话");
        }
        return participant;
    }
}
