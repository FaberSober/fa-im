-- ------------------------- info -------------------------
-- @@ver: 1_000_001
-- @@info: 完善fa-im字段约束和查询索引
-- ------------------------- info -------------------------

UPDATE `im_participant`
SET `unread_count` = 0
WHERE `unread_count` IS NULL;

UPDATE `im_message`
SET `is_withdrawn` = 0
WHERE `is_withdrawn` IS NULL;

ALTER TABLE `im_participant`
    MODIFY COLUMN `unread_count` int(11) NOT NULL DEFAULT 0 COMMENT '未读消息数量';

ALTER TABLE `im_message`
    MODIFY COLUMN `is_withdrawn` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否撤回';

CREATE INDEX `idx_im_message_conversation_id_id`
    ON `im_message` (`conversation_id`, `id`);

CREATE INDEX `idx_im_participant_user_id`
    ON `im_participant` (`user_id`);
