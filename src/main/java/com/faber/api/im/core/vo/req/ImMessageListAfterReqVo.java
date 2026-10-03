package com.faber.api.im.core.vo.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.io.Serializable;

/** 按消息ID补查新消息，首次加载仍使用历史分页接口。 */
@Data
public class ImMessageListAfterReqVo implements Serializable {

    @NotNull
    @Positive
    private Long conversationId;

    /** 空会话使用0，非空会话使用已补查的服务端消息ID。 */
    @NotNull
    @PositiveOrZero
    private Long afterMessageId;
}
