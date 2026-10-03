package com.faber.api.im.core.vo.ret;

import com.faber.api.im.core.entity.ImMessage;
import lombok.Data;

import java.util.List;

/** 按游标向后分页，不改变已读状态。 */
@Data
public class ImMessagePageAfterRetVo {
    private List<ImMessage> rows;
    private boolean hasNewer;
}
