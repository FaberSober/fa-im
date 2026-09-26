# fa-im 即时通讯模块

`fa-im` 是 FA Admin 的即时通讯模块。后端提供会话、群成员、消息和已读接口；管理端界面位于 `frontend/apps/admin/features/fa-im-pages`。

## 已实现功能

- 单聊创建与去重；群聊创建、添加成员、移除成员、退出和重命名。
- 按当前用户查询会话、群成员和历史消息；更新自己的会话已读状态与未读数。
- 发送文本、图片、视频和文件消息。文件消息保存文件元数据。
- 新消息、群成员和群名变化通过 WebSocket 通知相关用户。
- 会话与消息接口校验当前用户的成员身份，群聊管理操作校验管理员权限。

## 尚未实现

好友与好友分组、群公告、管理员转移、群聊解散、消息撤回、语音和表情消息。专项自动化测试见 [优化计划](docs/adrs/ADR-fa-im-001-im-optimization-plan.md) 的进度。

## 数据库

模块通过 `FaImDbInit` 注册为 `fa-im`，随宿主应用的数据库初始化流程按版本执行脚本。根据数据库类型选择以下目录，按 `1.0.0`、`1.0.1`、`1.0.2` 顺序执行；已有数据库只执行尚未应用的版本。

| 数据库 | 版本脚本目录 |
| --- | --- |
| MySQL | `src/main/resources/sql/fa-im/mysql/` |
| PostgreSQL | `src/main/resources/sql/fa-im/postgre/` |

`1.0.0` 创建 `im_conversation`、`im_participant`、`im_message`、`im_message_read` 四张表；`1.0.1` 补齐字段约束与查询索引；`1.0.2` 增加单聊唯一标识。`db/im-mysql.sql` 是旧版导出文件，不是当前版本升级入口。

## 主要接口

所有接口均位于 `/api/im/core/` 下。请求体和返回结构以控制器及前端 service 为准。

| 路径前缀 | 业务接口 |
| --- | --- |
| `imConversation` | `createNewSingle`、`createNewGroup`、`addGroupUsers`、`removeGroupUsers`、`exitGroupChat/{conversationId}`、`renameGroup`、`listQuery`、`sendMsg`、`updateConversationRead`、`getUnreadCount`、`getParticipant` |
| `imMessage` | `pageQuery` |

上述接口中 `getUnreadCount` 使用 GET，其余使用 POST。`imParticipant` 和 `imMessageRead` 控制器不提供通用 CRUD 业务接口。
