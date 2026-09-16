package com.faber.api.im.core.vo.req;

import java.io.Serializable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class ImConversationRenameReqVo implements Serializable {

    @NotNull
    @Positive
    private Long conversationId;

    /** 群聊名称 */
    @NotBlank
    @Size(max = 255)
    private String title;
    
}
