package com.faber.api.im.core.vo.req;

import java.io.Serializable;

import com.faber.api.im.core.enums.ImMessageTypeEnum;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class ImConversationSendMsgReqVo implements Serializable {
    
    @NotNull
    @Positive
    private Long conversationId;

    @NotBlank
    @Size(max = 10000)
    private String content;

    /** 可选客户端消息标识；重试时必须复用原标识。 */
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    private String clientMessageId;

    @NotNull
    private ImMessageTypeEnum type;

}
