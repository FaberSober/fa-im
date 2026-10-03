package com.faber.api.im.core.rest;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.faber.api.im.core.biz.ImMessageBiz;
import com.faber.api.im.core.entity.ImMessage;
import com.faber.api.im.core.vo.req.ImMessagePageQueryVo;
import com.faber.api.im.core.vo.req.ImMessageListAfterReqVo;
import com.faber.core.vo.msg.Ret;
import java.util.List;
import com.faber.core.annotation.FaLogBiz;
import com.faber.core.annotation.FaLogOpr;
import com.faber.core.annotation.LogNoRet;
import com.faber.core.enums.LogCrudEnum;
import com.faber.core.utils.BaseResHandler;
import com.faber.core.vo.msg.TableRet;
import com.faber.core.vo.query.BasePageQuery;

/**
 * IM-消息表
 *
 * @author xu.pengfei
 * @email 1508075252@qq.com
 * @date 2025-09-07 21:51:31
 */
@FaLogBiz("IM-消息表")
@RestController
@RequestMapping("/api/im/core/imMessage")
public class ImMessageController extends BaseResHandler {

    @Resource
    private ImMessageBiz baseBiz;


    @FaLogOpr(value = "分页查询", crud = LogCrudEnum.R)
    @LogNoRet
    @RequestMapping(value = "/pageQuery", method = RequestMethod.POST)
    @ResponseBody
    public TableRet<ImMessage> pageQuery(@Valid @RequestBody BasePageQuery<ImMessagePageQueryVo> query) {
        return baseBiz.pageQuery(query);
    }

    @FaLogOpr(value = "补查新消息", crud = LogCrudEnum.R)
    @LogNoRet
    @RequestMapping(value = "/listAfter", method = RequestMethod.POST)
    @ResponseBody
    public Ret<List<ImMessage>> listAfter(@Valid @RequestBody ImMessageListAfterReqVo query) {
        return ok(baseBiz.listAfter(query));
    }

}
