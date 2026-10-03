-- ------------------------- info -------------------------
-- @@ver: 1_000_004
-- @@info: 增加IM发送消息幂等标识
-- ------------------------- info -------------------------

ALTER TABLE `im_message`
    ADD COLUMN `client_message_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT '客户端消息幂等标识';

CREATE UNIQUE INDEX `uk_im_message_client`
    ON `im_message` (`conversation_id`, `sender_id`, `client_message_id`);
