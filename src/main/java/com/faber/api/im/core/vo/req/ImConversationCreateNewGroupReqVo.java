package com.faber.api.im.core.vo.req;

import java.io.Serializable;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;

import lombok.Data;

@Data
public class ImConversationCreateNewGroupReqVo implements Serializable {

    /** 可选源单聊；服务端保留原双方，创建独立群聊。 */
    @Positive
    private Long sourceConversationId;

    /** 群聊用户ID（指定源单聊时为新增用户） */
    @NotEmpty
    @Size(min = 1, max = 100)
    private List<@NotBlank @Size(max = 32) String> userIds;
    
}
