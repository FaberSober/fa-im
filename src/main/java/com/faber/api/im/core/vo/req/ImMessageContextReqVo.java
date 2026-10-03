package com.faber.api.im.core.vo.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/** 聊天记录定位及向后分页游标。 */
@Data
public class ImMessageContextReqVo implements Serializable {
    @NotNull
    @Positive
    private Long conversationId;

    @NotNull
    @Positive
    private Long messageId;
}
