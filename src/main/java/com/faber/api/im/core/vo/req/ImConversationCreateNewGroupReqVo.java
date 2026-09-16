package com.faber.api.im.core.vo.req;

import java.io.Serializable;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class ImConversationCreateNewGroupReqVo implements Serializable {

    /** 群聊用户ID */
    @NotEmpty
    @Size(min = 3, max = 100)
    private List<@NotBlank @Size(max = 32) String> userIds;
    
}
