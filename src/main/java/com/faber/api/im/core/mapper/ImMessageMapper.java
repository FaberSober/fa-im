package com.faber.api.im.core.mapper;

import java.util.List;
import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.vo.req.ImMessagePageQueryVo;
import com.faber.api.im.core.vo.req.ImMessageListAfterReqVo;
import com.faber.api.im.core.vo.ret.ImMessageSearchRetVo;
import com.faber.core.config.mybatis.base.FaBaseMapper;

/**
 * IM-消息表
 * 
 * @author xu.pengfei
 * @email 1508075252@qq.com
 * @date 2025-09-07 21:51:31
 */
public interface ImMessageMapper extends FaBaseMapper<ImMessage> {
	
    /** 当前读避免 MySQL 可重复读快照遗漏并发事务刚提交的消息。 */
    @Select("SELECT * FROM im_message WHERE conversation_id = #{conversationId} "
        + "AND sender_id = #{senderId} AND client_message_id = #{clientMessageId} AND deleted = false FOR UPDATE")
    ImMessage findClientMessage(@Param("conversationId") Long conversationId,
        @Param("senderId") String senderId, @Param("clientMessageId") String clientMessageId);

    /** 锁定子查询使用当前读；外层计数兼容 PostgreSQL 不允许聚合直接 FOR UPDATE。 */
    @Select("SELECT COUNT(*) FROM (SELECT id FROM im_message WHERE conversation_id = #{conversationId} "
        + "AND sender_id != #{userId} AND id > #{lastReadMessageId} AND deleted = false FOR UPDATE) unread_messages")
    Long countUnreadForUpdate(@Param("conversationId") Long conversationId,
        @Param("userId") String userId, @Param("lastReadMessageId") Long lastReadMessageId);

    /** 查询任务 */
    List<ImMessage> pageQuery(@Param("query") ImMessagePageQueryVo queryVo);

    List<ImMessage> listAfter(@Param("query") ImMessageListAfterReqVo queryVo);

    List<ImMessageSearchRetVo> searchText(@Param("conversationId") Long conversationId,
        @Param("escapedKeyword") String escapedKeyword, @Param("senderId") String senderId,
        @Param("startTime") LocalDateTime startTime, @Param("endTimeExclusive") LocalDateTime endTimeExclusive);

    ImMessage contextTarget(@Param("conversationId") Long conversationId, @Param("messageId") Long messageId);

    List<ImMessage> contextBefore(@Param("conversationId") Long conversationId, @Param("messageId") Long messageId);

    List<ImMessage> contextAfter(@Param("conversationId") Long conversationId, @Param("messageId") Long messageId);

}
