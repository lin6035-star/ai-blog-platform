package com.hailin.blogsystem.entity.dto;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import lombok.Data;

/**
 * 验证码校验请求。
 *
 * <p><b>字段名不能改</b>：tianai 前端 SDK 在 {@code config.js} 里写死了报文格式
 * {@code sendParam = {id: currentCaptchaId, data: data}}，服务端必须按同名接收，
 * 否则轨迹反序列化不到、校验必然失败。
 *
 * <p><b>刻意不加 {@code @Valid}</b>：这个报文由 SDK 生成，不面对人手输入。
 * 真出了格式问题会在匹配阶段以「验证失败」暴露，与参数错误对用户是同一个动作
 * （重滑一次），没必要为此多一个错误码分支。
 */
@Data
public class CaptchaCheckDTO {

    /** 验证码 id，由生成接口下发 */
    private String id;

    /** 滑动轨迹（背景图尺寸 / 起止时间 / 轨迹点列表），由 SDK 采集 */
    private ImageCaptchaTrack data;
}
