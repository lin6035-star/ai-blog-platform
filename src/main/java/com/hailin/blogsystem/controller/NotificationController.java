package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.NotificationReadDTO;
import com.hailin.blogsystem.entity.vo.NotificationVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.service.NotificationService;
import com.hailin.blogsystem.utils.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 站内通知（登录用户自己的资源）。
 *
 * <p>路径按 api-convention 的「当前登录用户自己的资源用 {@code /api/users/me/...}」约定，
 * 于是**自动落在 JwtInterceptor 的保护范围内**（WebMvcConfig:54 的 {@code /api/users/me/**}），
 * 这里不需要再加鉴权配置。
 *
 * <p>权限边界不靠路径，靠 SQL：service 里每条查询/更新都带 {@code receiver_id = 当前用户}。
 */
@RestController
@RequestMapping("/api/users/me/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 通知列表。传统分页（与评论 / 账单一致）——它是有页码语义的列表，不是无限滚动 */
    @GetMapping("")
    public Result<PageVO<NotificationVO>> listNotifications(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "20") Long pageSize) {
        return Result.success(notificationService.listMine(page, pageSize));
    }

    /** 未读数。header 红点用，返回裸数字 */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(notificationService.unreadCount());
    }

    /** 标记指定通知已读 */
    @PostMapping("/read")
    public Result<Void> markRead(@Valid @RequestBody NotificationReadDTO dto) {
        notificationService.markRead(dto.getIds());
        return Result.success();
    }

    /** 全部已读 */
    @PostMapping("/read-all")
    public Result<Void> markAllRead() {
        notificationService.markAllRead();
        return Result.success();
    }
}
