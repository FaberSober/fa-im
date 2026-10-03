package com.faber.api.im.core.mapper;

import com.faber.core.config.mybatis.base.FaBaseMapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.faber.api.im.core.entity.ImConversation;
import com.faber.api.im.core.entity.ImParticipant;
import com.faber.api.im.core.vo.req.ImConversationGetParticipantReqVo;
import com.faber.api.im.core.vo.req.ImConversationListQueryReqVo;
import com.faber.api.im.core.vo.ret.ImConversationRetVo;

/**
 * IM-会话表
 * 
 * @author xu.pengfei
 * @email 1508075252@qq.com
 * @date 2025-09-07 21:51:31
 */
public interface ImConversationMapper extends FaBaseMapper<ImConversation> {
	
    /** 发送事务锁定会话行，所有实例共享同一数据库锁。 */
    @Select("SELECT id FROM im_conversation WHERE id = #{conversationId} AND deleted = false FOR UPDATE")
    Long lockForSend(@Param("conversationId") Long conversationId);

    List<ImConversationRetVo> listQuery(@Param("userId") String userId, @Param("query") ImConversationListQueryReqVo reqVo);

    Integer countUnreadByUserId(@Param("userId") String userId);

    void updateUnreadByConvId(@Param("conversationId") Long conversationId, @Param("excludeUserId") String excludeUserId);

    List<ImParticipant> getParticipant(@Param("query") ImConversationGetParticipantReqVo reqVo);
}
