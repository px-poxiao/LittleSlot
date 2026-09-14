# PlaceholderAPI 支持

把 PlaceholderAPI 和 LittleSlot 安装在同一 Bukkit/Paper 服务器并重启。LittleSlot 会自动注册内置的 `littleslot` 扩展；没有安装 PlaceholderAPI 时，LittleSlot 仍能正常运行。无需执行 `/papi ecloud download`。

| 占位符 | 返回值 |
| --- | --- |
| `%littleslot_state%` | 当前会话阶段的小写英文名；不在线或无会话时为 `none` |
| `%littleslot_scope%` | 服务器当前配置的名额共享范围 |
| `%littleslot_uid%` | 已获 LittleSkin 名额时的 UID，其他阶段为空 |
| `%littleslot_used%` | 该 UID 当前已占名额数，其他阶段为空 |
| `%littleslot_limit%` | 该 UID 的上限，`-1` 表示无限；其他阶段为空 |
| `%littleslot_remaining%` | 剩余可新增名额数，`-1` 表示无限；其他阶段为空 |

`state` 表示 LittleSlot 的**处理阶段**，不是登录插件的鉴权结果。可能值为：`checking`（入服查询中）、`choosing`（Mojang 超时，等待玩家选择）、`resolving`（本地归属/数据库处理中）、`binding`（等待 LittleSkin 授权）、`admitted`（LittleSkin 名额通过）、`temporary`（OAuth 故障临时放行）、`bypass`（无需名额或本次临时绕过）、`denied`（已拒绝）。`bypass` 可能来自正版 UUID 匹配、Mojang 超时后自选正版，或服主关闭强制绑定；不要把它显示为“正版已验证”。

这些占位符只读取当前在线会话的缓存，不查询数据库；数字只在 `admitted` 状态有值。管理员在玩家入服后调整上限时，缓存中的数字要等该玩家下次完成入服检查才更新。因此适合计分板显示当前会话，不适合做权限或计费判断。
