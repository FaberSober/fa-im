package com.faber.api.im.core.vo.req;

import java.io.Serializable;

import com.faber.api.im.core.enums.ImMessageTypeEnum;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

    @NotNull
    private ImMessageTypeEnum type;

}
