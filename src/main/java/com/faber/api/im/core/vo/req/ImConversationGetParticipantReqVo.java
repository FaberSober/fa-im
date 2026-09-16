package com.faber.api.im.core.vo.req;

import java.io.Serializable;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class ImConversationGetParticipantReqVo implements Serializable {

    @NotNull
    @Positive
    private Long conversationId;

    @Size(max = 255)
    private String name;

}
