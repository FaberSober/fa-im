package com.faber.api.im.core.vo.req;

import java.io.Serializable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 会话内文本搜索。关键词长度在去除首尾空格后校验。 */
@Data
public class ImMessageSearchTextReqVo implements Serializable {

    @NotNull
    @Positive
    private Long conversationId;

    private String keyword;

    private String senderId;

    /** 日期按业务本地时间解释，格式 yyyy-MM-dd。 */
    private String startDate;

    private String endDate;
}
