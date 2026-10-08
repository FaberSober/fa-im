package com.faber.api.im.core.vo.req;

import java.io.Serializable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 会话内搜索筛选。文件模式按文件名及扩展名查询；图片模式只接受空关键词。 */
@Data
public class ImMessageSearchTextReqVo implements Serializable {

    @NotNull
    @Positive
    private Long conversationId;

    private String keyword;

    /** 仅文件模式使用，按扩展名精确匹配，允许前导点。 */
    private String fileExt;

    private String senderId;

    /** 日期按业务本地时间解释，格式 yyyy-MM-dd。 */
    private String startDate;

    private String endDate;
}
