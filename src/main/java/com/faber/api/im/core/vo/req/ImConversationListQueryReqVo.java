package com.faber.api.im.core.vo.req;

import java.io.Serializable;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class ImConversationListQueryReqVo implements Serializable {
    
    @Size(max = 255)
    private String title;

    @Positive
    private Long conversationId;

}
