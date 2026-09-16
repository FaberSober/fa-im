package com.faber.api.im.core.biz;

import org.springframework.stereotype.Service;

import com.faber.api.im.core.entity.ImParticipant;
import com.faber.api.im.core.mapper.ImParticipantMapper;
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

    /** 校验当前用户是否属于指定会话。 */
    public ImParticipant requireParticipant(Long conversationId, String userId) {
        if (conversationId == null || userId == null) {
            throw new BuzzException("会话参数不能为空");
        }
        if (conversationId <= 0) {
            throw new BuzzException("会话ID必须为正数");
        }

        ImParticipant participant = lambdaQuery()
            .eq(ImParticipant::getConversationId, conversationId)
            .eq(ImParticipant::getUserId, userId)
            .one();
        if (participant == null) {
            throw new BuzzException("无权访问该会话");
        }
        return participant;
    }
}
