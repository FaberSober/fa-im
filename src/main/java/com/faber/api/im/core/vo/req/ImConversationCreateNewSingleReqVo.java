package com.faber.api.im.core.vo.req;

import java.io.Serializable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class ImConversationCreateNewSingleReqVo implements Serializable {

    /** 单聊对方用户ID */
    @NotBlank
    @Size(max = 32)
    private String toUserId;
    
}
