package com.faber.api.im.core.vo.req;

import java.io.Serializable;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;

@Data
public class ImConversationUpdateReadReqVo implements Serializable {
    
    @NotNull
    @Positive
    private Long conversationId;

    /** 客户端实际展示的最新消息；为空时兼容旧客户端，读取当前最新消息。 */
    @Positive
    private Long lastReadMessageId;

}
