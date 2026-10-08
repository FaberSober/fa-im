package com.faber.api.im.core.biz;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.enums.ImMessageTypeEnum;
import com.faber.api.im.core.mapper.ImMessageMapper;
import com.faber.api.im.core.vo.req.ImMessagePageQueryVo;
import com.faber.api.im.core.vo.req.ImMessageListAfterReqVo;
import com.faber.api.im.core.vo.req.ImMessageSearchTextReqVo;
import com.faber.api.im.core.vo.ret.ImMessageSearchRetVo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import com.faber.api.im.core.vo.req.ImMessageContextReqVo;
import com.faber.api.im.core.vo.ret.ImMessageContextRetVo;
import com.faber.api.im.core.vo.ret.ImMessagePageAfterRetVo;
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

    /** 当前成员可搜索会话全部历史；不修改已读状态。 */
    public TableRet<ImMessageSearchRetVo> searchText(BasePageQuery<ImMessageSearchTextReqVo> query) {
        return searchMessages(query, ImMessageTypeEnum.TEXT);
    }

    /** 浏览或筛选会话图片；不修改已读状态。 */
    public TableRet<ImMessageSearchRetVo> searchImages(BasePageQuery<ImMessageSearchTextReqVo> query) {
        return searchMessages(query, ImMessageTypeEnum.IMAGE);
    }

    /** 按文件名和扩展名浏览或筛选会话文件；不修改已读状态。 */
    public TableRet<ImMessageSearchRetVo> searchFiles(BasePageQuery<ImMessageSearchTextReqVo> query) {
        return searchMessages(query, ImMessageTypeEnum.FILE);
    }

    private TableRet<ImMessageSearchRetVo> searchMessages(BasePageQuery<ImMessageSearchTextReqVo> query, ImMessageTypeEnum type) {
        if (query == null || query.getQuery() == null || query.getQuery().getConversationId() == null
                || query.getQuery().getConversationId() <= 0) {
            throw new BuzzException("会话ID必须为正数");
        }
        if (query.getCurrent() < 1 || query.getPageSize() < 1 || query.getPageSize() > 100) {
            throw new BuzzException("分页参数无效，每页最多查询100条消息");
        }
        var filters = query.getQuery();
        String keyword = normalizedSearchValue(filters.getKeyword());
        String senderId = normalizedSearchValue(filters.getSenderId());
        if (type == ImMessageTypeEnum.IMAGE && keyword != null) {
            throw new BuzzException("图片搜索不支持文本关键词");
        }
        if (keyword != null && keyword.length() > 100) {
            throw new BuzzException("搜索关键词最多100字");
        }
        if (senderId != null && senderId.length() > 100) {
            throw new BuzzException("发送者ID最多100字");
        }
        String fileExt = normalizedSearchValue(filters.getFileExt());
        if (fileExt != null) {
            if (type != ImMessageTypeEnum.FILE) throw new BuzzException("仅文件搜索支持扩展名筛选");
            if (fileExt.startsWith(".")) fileExt = fileExt.substring(1);
            if (!fileExt.matches("[A-Za-z0-9]{1,12}")) throw new BuzzException("文件类型须为1至12位字母或数字扩展名");
            fileExt = fileExt.toLowerCase(java.util.Locale.ROOT);
        }
        LocalDate startDate = parseSearchDate(filters.getStartDate());
        LocalDate endDate = parseSearchDate(filters.getEndDate());
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BuzzException("开始日期不能晚于结束日期");
        }
        if (type == ImMessageTypeEnum.TEXT && keyword == null && senderId == null && startDate == null && endDate == null) {
            throw new BuzzException("请填写关键词或选择成员、日期筛选");
        }
        Long conversationId = filters.getConversationId();
        imParticipantBiz.requireParticipant(conversationId, getCurrentUserId());
        // 转义和日期边界均由服务端生成，不能由请求覆盖；发送者可以是历史成员。
        String escapedKeyword = keyword == null ? null : keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        LocalDateTime startTime = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endTimeExclusive = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        String normalizedExt = fileExt;
        PageInfo<ImMessageSearchRetVo> info = PageHelper.startPage(query.getCurrent(), query.getPageSize())
                .doSelectPageInfo(() -> {
                    if (type == ImMessageTypeEnum.IMAGE) {
                        baseMapper.searchImages(conversationId, senderId, startTime, endTimeExclusive);
                    } else if (type == ImMessageTypeEnum.FILE) {
                        baseMapper.searchFiles(conversationId, escapedKeyword, normalizedExt, senderId, startTime, endTimeExclusive);
                    } else {
                        baseMapper.searchText(conversationId, escapedKeyword, senderId, startTime, endTimeExclusive);
                    }
                });
        return new TableRet<>(info);
    }

    private String normalizedSearchValue(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private LocalDate parseSearchDate(String value) {
        String date = normalizedSearchValue(value);
        if (date == null) return null;
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new BuzzException("日期格式必须为yyyy-MM-dd");
        }
        try {
            LocalDate parsed = LocalDate.parse(date);
            if (parsed.getYear() < 1 || parsed.equals(LocalDate.of(9999, 12, 31))) {
                throw new BuzzException("日期超出支持范围");
            }
            return parsed;
        } catch (DateTimeParseException e) {
            throw new BuzzException("日期无效");
        }
    }

    /** 定位目标消息，前后各最多20条；查询本身不修改已读。 */
    public ImMessageContextRetVo context(ImMessageContextReqVo query) {
        validateContextQuery(query);
        imParticipantBiz.requireParticipant(query.getConversationId(), getCurrentUserId());
        ImMessage target = baseMapper.contextTarget(query.getConversationId(), query.getMessageId());
        if (target == null || Boolean.TRUE.equals(target.getIsWithdrawn())) {
            throw new BuzzException("原消息已不存在或已撤回");
        }
        List<ImMessage> older = baseMapper.contextBefore(query.getConversationId(), query.getMessageId());
        List<ImMessage> newer = baseMapper.contextAfter(query.getConversationId(), query.getMessageId());
        List<ImMessage> rows = new ArrayList<>(older.subList(0, Math.min(20, older.size())));
        Collections.reverse(rows);
        rows.add(target);
        rows.addAll(newer.subList(0, Math.min(20, newer.size())));
        ImMessageContextRetVo result = new ImMessageContextRetVo();
        result.setRows(rows);
        result.setHasOlder(older.size() > 20);
        result.setHasNewer(newer.size() > 20);
        return result;
    }

    /** 向后分页允许游标消息已被删除；只校验会话成员和游标格式。 */
    public ImMessagePageAfterRetVo pageAfter(ImMessageContextReqVo query) {
        validateContextQuery(query);
        imParticipantBiz.requireParticipant(query.getConversationId(), getCurrentUserId());
        List<ImMessage> messages = baseMapper.contextAfter(query.getConversationId(), query.getMessageId());
        ImMessagePageAfterRetVo result = new ImMessagePageAfterRetVo();
        result.setRows(new ArrayList<>(messages.subList(0, Math.min(20, messages.size()))));
        result.setHasNewer(messages.size() > 20);
        return result;
    }

    private void validateContextQuery(ImMessageContextReqVo query) {
        if (query == null || query.getConversationId() == null || query.getConversationId() <= 0
                || query.getMessageId() == null || query.getMessageId() <= 0) {
            throw new BuzzException("会话ID和消息ID必须为正数");
        }
    }

}
