package com.faber.api.im.core.vo.ret;

import com.faber.api.im.core.entity.ImMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 搜索结果附带发送者姓名，不改变常规消息响应。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ImMessageSearchRetVo extends ImMessage {
    private String senderName;
}
