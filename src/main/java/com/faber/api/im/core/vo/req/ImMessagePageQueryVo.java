package com.faber.api.im.core.vo.req;

import java.io.Serializable;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;

@Data
public class ImMessagePageQueryVo implements Serializable {
    
    @Positive
    private Long maxMsgId;

    @NotNull
    @Positive
    private Long conversationId;

}
