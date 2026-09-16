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

}
