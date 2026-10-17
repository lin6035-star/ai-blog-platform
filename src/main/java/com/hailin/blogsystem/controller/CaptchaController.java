package com.hailin.blogsystem.controller;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.common.response.ApiResponse;
import com.hailin.blogsystem.entity.dto.CaptchaCheckDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行为验证码（混搭：滑块 / 旋转 / 滑动还原 / 文字点选）。
 *
 * <p><b>⚠️ 这两个接口的响应体是 tianai 的 {@link ApiResponse}，不是项目统一的
 * {@code Result}，也不能改。</b> 前端 SDK 在 {@code captcha.js} 里硬判定：
 * <pre>
 *   if (!data.code) throw ...              // 顶层必须有 code 且非 0
 *   let type = data.code === 200 ? data.data?.type : "DISABLED"
 * </pre>
 * 套一层 {@code Result}（成功码是 0）会直接命中第一个判断抛错，验证码整块显示不出来。
 * 这是引入第三方 SDK 必须付的一次契约不一致，收窄在 {@code /api/auth/captcha/**} 两个端点上。
 *
 * <p><b>不需要登录</b>：{@code /api/auth/**} 不在 {@code WebMvcConfig} 里
 * {@code jwtInterceptor} 的路径列表内，天然放行——登录前本来也没有 token。
 *
 * <p><b>POST 而不是 GET</b>：SDK 固定用 POST + {@code {}} 空 JSON 请求生成接口
 * （{@code requestParam.method = "POST"}），改成 GET 会 405。
 */
@RestController
@RequestMapping("/api/auth/captcha")
public class CaptchaController {

    /** 本版本后端（1.5.5）实际装了生成器的四种类型；POW 前端认识但后端没有生成器，不能放进池子 */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            CaptchaTypeConstant.SLIDER,
            CaptchaTypeConstant.ROTATE,
            CaptchaTypeConstant.CONCAT,
            CaptchaTypeConstant.WORD_IMAGE_CLICK
    );

    private final ImageCaptchaApplication imageCaptchaApplication;
    private final List<String> types;
    private final SecureRandom random = new SecureRandom();

    public CaptchaController(ImageCaptchaApplication imageCaptchaApplication,
                             @Value("${blog.captcha.types:SLIDER,ROTATE,CONCAT,WORD_IMAGE_CLICK}")
                             List<String> types) {
        // 配置里的类型名写错时启动就炸：否则表现为「随机某种类型生成失败」，
        // 用户看到的是时好时坏的验证码，几乎无法归因
        for (String type : types) {
            if (!SUPPORTED_TYPES.contains(type)) {
                throw new IllegalStateException(
                        "blog.captcha.types 里有不支持的类型: " + type + "，可选 " + SUPPORTED_TYPES);
            }
        }
        if (types.isEmpty()) {
            throw new IllegalStateException("blog.captcha.types 不能为空");
        }

        this.imageCaptchaApplication = imageCaptchaApplication;
        this.types = List.copyOf(types);
    }

    /**
     * 生成验证码：从 {@code blog.captcha.types} 里等概率随机挑一种。
     *
     * <p>混搭的收益是**成本叠加**：攻击者得为滑块、旋转、还原、点选各写一套方案，
     * 而不用混搭时只需攻破一种。代价见 {@code docs/登录验证码设计.md}。
     *
     * <p>返回的 {@code data.type} 决定前端渲染哪个组件——**前端零改动**，SDK 自己分发。
     */
    @PostMapping
    public ApiResponse<ImageCaptchaVO> generate() {
        String type = types.get(random.nextInt(types.size()));
        return imageCaptchaApplication.generateCaptcha(type);
    }

    /**
     * 校验轨迹。四种类型走同一个端点：区分逻辑在服务端
     * （{@code SimpleImageCaptchaValidator} 按缓存里的 {@code type} 分发到
     * 滑块校验或点选校验），前端只管提交自己采集到的轨迹。
     *
     * <p>成功时把 {@code id} 原样回给前端（SDK 读 {@code res.data.token}），
     * 它是后续登录 / 注册要带的凭证——不额外发 token，因为「二次验证」记录本身就是
     * 以 {@code captcha:secondary:{id}} 为 key 存的（见 CaptchaVerifier）。
     */
    @PostMapping("/check")
    public ApiResponse<?> check(@RequestBody CaptchaCheckDTO captchaCheckDTO) {
        ApiResponse<?> result = imageCaptchaApplication.matching(
                captchaCheckDTO.getId(), captchaCheckDTO.getData());

        if (result.isSuccess()) {
            return ApiResponse.ofSuccess(Map.of("token", captchaCheckDTO.getId()));
        }
        // 失败原样透传：SDK 认 4001 是「验证码过期」，其余码显示「验证码被黑洞吸走了」，
        // 换成自己的错误码会让这两种提示都失真
        return result;
    }
}
