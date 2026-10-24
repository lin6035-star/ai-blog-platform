# Comment IP Location Plan Archive

这份文件是 2026-07-13 的评论 IP 属地实现计划归档，不再作为当前待办使用。

当前实现状态：

- `ClientIpUtils` 已用于解析 `CF-Connecting-IP`、`X-Forwarded-For`、`X-Real-IP` 和远端地址。
- `IpLocationService` 已接入 `ip2region_v4.xdb`，支持本地网络、国内省份、海外国家和未知兜底。
- 评论创建时由后端解析并保存 `ip` / `ipLocation`，前端不能提交或覆盖。
- `deploy/nginx.conf` 已保留 `CF-Connecting-IP` 转发。
- 当前接口约定见 `docs/api-convention.md` 的“评论接口”部分。

历史设计仍可参考：

- `docs/superpowers/specs/2026-07-13-comment-ip-location-design.md`
