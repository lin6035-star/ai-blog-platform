# Comment IP Location Design

## Goal

When an authenticated user posts a comment, the backend records the real client IP and a coarse location. The client cannot submit or override either field, and comment creation still succeeds if location lookup fails.

## Design

- `ClientIpUtils` reads `CF-Connecting-IP` first because production traffic is proxied by Cloudflare, then falls back to the first `X-Forwarded-For` value, `X-Real-IP`, and `request.getRemoteAddr()` for local development.
- `IpLocationService` loads the bundled `ip2region_v4.xdb` database once and performs offline lookups. Chinese results show the province; other results show the country. Loopback and private addresses display `本地网络`; invalid or failed lookups display `未知`.
- `CommentsController` extracts the IP from the HTTP request and passes it to `CommentsService`. `CommentsServiceImpl` resolves and stores the location when creating the comment.
- `CommentDTO` contains only user-editable fields: `content` and `parentId`.

## Security And Failure Handling

- Request-body `ip` and `ipLocation` fields are ignored by removing them from the DTO.
- `CF-Connecting-IP` is trusted only for traffic that reaches the site through Cloudflare. Restricting direct origin access remains a later deployment-hardening task.
- A location lookup failure returns `未知` instead of rejecting the comment.

## Verification

- Unit tests cover request-header priority and fallback behavior.
- Service tests cover local/private/invalid IP handling and comment persistence.
- Controller integration tests verify that a Cloudflare IP header is stored while forged request-body fields are not accepted as writable DTO properties.
