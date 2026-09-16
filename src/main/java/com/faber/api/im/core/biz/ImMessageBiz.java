package com.faber.api.im.core.biz;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.mapper.ImMessageMapper;
import com.faber.api.im.core.vo.req.ImMessagePageQueryVo;
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

    public TableRet<ImMessage> pageQuery(BasePageQuery<ImMessagePageQueryVo> query) {
        if (query == null || query.getQuery() == null || query.getQuery().getConversationId() == null) {
            throw new BuzzException("会话ID不能为空");
        }
        imParticipantBiz.requireParticipant(query.getQuery().getConversationId(), getCurrentUserId());

        PageInfo<ImMessage> info = PageHelper.startPage(query.getCurrent(), query.getPageSize())
                .doSelectPageInfo(() -> baseMapper.pageQuery(query.getQuery()));
        return new TableRet<>(info);
    }
}
