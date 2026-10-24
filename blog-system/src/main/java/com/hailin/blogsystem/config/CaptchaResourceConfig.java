package com.hailin.blogsystem.config;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.common.constant.CommonConstant;
import cloud.tianai.captcha.resource.ResourceStore;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.resource.impl.LocalMemoryResourceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 滑块验证码的资源（当前只有背景图）。
 *
 * <p><b>为什么必须显式提供</b>：{@code captcha.init-default-resource=true} 这个名字很容易误解——
 * 它调的 {@code TACBuilder.addDefaultTemplate(...)} 走的是 {@code DefaultBuiltInResources}，
 * 而那个类的 {@code PATH_PREFIX} 是 {@code classpath:META-INF/cut-image/template}，
 * <b>只装模板（滑块/旋转的 active+fixed 图），不装背景图</b>。
 * 少了背景图时生成接口不会说"资源为空"，而是抛
 * 「请求的资源数量超过可用资源总数」——一句话完全指不到根因（实测踩到）。
 *
 * <p>背景图直接引用 tianai jar 里自带的那张 {@code META-INF/cut-image/resource/1.jpg}
 * （600×360，正是滑块需要的尺寸）：不引入任何二进制资源进本仓库，
 * 也避免自己找图的版权问题。<b>代价是所有滑块共用一张背景</b>——
 * 缺口位置每次随机，功能与安全性不受影响，只是视觉上单调；
 * 想要多张就往 {@link ResourceStore} 里多 add 几条（放自己的图到 resources 下即可）。
 *
 * <p>定义这个 bean 会**替换掉 starter 默认的 {@code RedisResourceStore}**
 * （它带 {@code @ConditionalOnMissingBean(ResourceStore.class)}）。
 * 这是有意的：图片打包在 jar 里，每个实例自己读 classpath 即可，
 * 不需要把图片本体塞进 Redis。真正必须共享的是**验证码数据**（CacheStore），那部分仍然是 Redis。
 */
@Configuration
public class CaptchaResourceConfig {

    /** 背景图：tianai jar 自带，600×360，正好是滑块/旋转需要的尺寸 */
    private static final String BACKGROUND = "META-INF/cut-image/resource/1.jpg";

    /**
     * 混搭池里的类型**每种都要有背景图**，缺一种就会轮到它时报
     * 「请求的资源数量超过可用资源总数」（见类注释）。
     */
    private static final List<String> TYPES_WITH_BACKGROUND = List.of(
            CaptchaTypeConstant.SLIDER,
            CaptchaTypeConstant.ROTATE,
            CaptchaTypeConstant.CONCAT,
            CaptchaTypeConstant.WORD_IMAGE_CLICK
    );

    @Bean
    public ResourceStore captchaResourceStore() {
        LocalMemoryResourceStore store = new LocalMemoryResourceStore();

        // 同一张图给四种类型共用：缺口位置 / 旋转角度 / 碎片顺序 / 点选文字都是每次随机的，
        // 底图共用不影响任何一种的校验逻辑
        for (String type : TYPES_WITH_BACKGROUND) {
            store.addResource(type, new Resource("classpath", BACKGROUND, CommonConstant.DEFAULT_TAG));
        }

        return store;
    }
}
