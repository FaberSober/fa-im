package com.faber.api.im.core.entity;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.faber.core.annotation.FaModalName;
import com.faber.core.annotation.SqlEquals;
import com.faber.core.bean.BaseTnCrtEntity;

import lombok.Data;
import java.util.Date;

/**
 * IM-会话参与者表
 *
 * @author xu.pengfei
 * @email 1508075252@qq.com
 * @date 2025-09-07 21:51:31
 */
@FaModalName(name = "IM-会话参与者表")
@TableName("im_participant")
@Data
public class ImParticipant extends BaseTnCrtEntity {

    @ColumnWidth(8)
    @ExcelProperty("ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @SqlEquals
    @ExcelProperty("会话ID")
    private Long conversationId;

    @SqlEquals
    @ExcelProperty("用户ID")
    private String userId;

    @SqlEquals
    @ExcelProperty("最后一条已读消息ID")
    private Long lastReadMessageId;

    @ExcelProperty("未读消息数量")
    private Integer unreadCount;
    
    // 如用户A与用户B的单聊：针对用户A，会话标题为用户B。针对用户B，会话标题为用户A
    @ExcelProperty("用户标题")
    private String title;

    @ExcelProperty("个人消息免打扰")
    private Boolean muted;

    @ExcelProperty("个人置顶")
    private Boolean pinned;

    @ExcelProperty("个人置顶时间")
    private Date pinnedTime;

    // ----------------- show cols -----------------
    @TableField(exist = false)
    @ExcelProperty("用户姓名")
    private String name;

    @TableField(exist = false)
    @ExcelProperty("用户头像")
    private String img;

}
