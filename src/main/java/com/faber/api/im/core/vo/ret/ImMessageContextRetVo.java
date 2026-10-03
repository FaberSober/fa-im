package com.faber.api.im.core.vo.ret;

import com.faber.api.im.core.entity.ImMessage;
import lombok.Data;

import java.util.List;

/** 目标消息及前后上下文，不改变已读状态。 */
@Data
public class ImMessageContextRetVo {
    private List<ImMessage> rows;
    private boolean hasOlder;
    private boolean hasNewer;
}
