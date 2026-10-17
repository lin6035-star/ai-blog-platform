package com.hailin.blogsystem.entity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 *
 * <p><b>校验边界对齐数据库列宽</b>（username / nickname 都是 varchar(50)，email 是 varchar(255)）：
 * 原先只在 controller 里校验「非空」，超长会一路写到库里才炸——那时返回的是 500，
 * 用户看到的是「服务器错误」而不是「你输入太长了」。
 *
 * <p><b>刻意不加「最少几位」这类下限</b>：那是**新增约束**，会拒绝掉原本能注册的输入。
 * 参数校验的目的是把「会在更深处炸掉的问题拦在前面」，不是借机收紧产品规则——
 * 真要加长度下限，应该是独立的一次产品决策，而不是顺手夹在工程化改造里。
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名最长 50 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码至少 8 个字符，最长 64 个字符")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称最长 50 个字符")
    private String nickname;

    /**
     * 邮箱：**找回密码的唯一通道**，必填（DB 上有唯一索引）。
     *
     * <p>为什么必填而不是可选：不留邮箱的账号一旦忘记密码就永久找不回——项目既没有
     * 客服也没有绑手机号，那等于给用户埋一条死路。老数据（邮箱为 NULL）走不了找回流程，
     * 属于可接受的过渡。
     *
     * <p>{@code @Email} 只做**格式**检查，不验证邮箱真实存在（那需要发验证邮件，是另一件事）。
     */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 255, message = "邮箱最长 255 个字符")
    private String email;

    /**
     * 验证码凭证：{@code POST /api/auth/captcha/check} 通过后拿到的一次性 id。
     * 语义同 {@link LoginDTO#getCaptchaVerification()}：消费即销毁，重放无效。
     */
    @NotBlank(message = "请先完成安全验证")
    private String captchaVerification;
}
