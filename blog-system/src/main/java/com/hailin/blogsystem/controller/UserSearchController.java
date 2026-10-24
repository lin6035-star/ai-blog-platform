package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UserRelationVO;
import com.hailin.blogsystem.service.UsersService;
import com.hailin.blogsystem.utils.PageParamUtils;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户搜索（公开接口，游客可访问）。
 *
 * <p><b>为什么单独一个 controller</b>：{@code /api/users} 已被 {@link UsersController}
 * 的「当前登录用户资料」占用，{@code /api/users/{id}} 是 {@link UserInfoController}。
 * 搜索既不读当前用户、也不按 id 取，跟这两个不是一类东西。
 *
 * <p>路径不冲突：Spring MVC 的路径匹配**字面量优先于变量段**，
 * {@code /api/users/search} 会命中本类，不会被 {@code /api/users/{id}} 吞掉。
 *
 * <p>权限：{@code /api/users/*} 在 {@code WebMvcConfig} 里挂的是
 * <b>OptionalJwtInterceptor</b>（可选登录），所以游客能搜，
 * 登录用户会额外带上 followed / self，拦截器配置不用改。
 */
@RestController
@RequestMapping("/api/users/search")
@RequiredArgsConstructor
public class UserSearchController {

    private final UsersService usersService;

    @GetMapping("")
    public Result<PageVO<UserRelationVO>> searchUsers(@RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") Long page,
                                                      @RequestParam(defaultValue = "10") Long pageSize) {
        // 公开接口：pageSize 负数会让 MP 跳过所有分页，入口归一化
        page = PageParamUtils.clampPage(page);
        pageSize = PageParamUtils.clampPageSize(pageSize, 10);
        return Result.success(usersService.searchUsers(keyword, page, pageSize));
    }
}
