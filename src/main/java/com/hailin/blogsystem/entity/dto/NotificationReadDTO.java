package com.hailin.blogsystem.entity.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 标记已读的请求体。
 *
 * <p>与「全部已读」分成两个接口，**不用"ids 为空 = 全部"这种隐式语义**——
 * 项目里"用类型/接口形状保证契约"的先例是钱包（请求体里根本没有金额字段）。
 * 空列表在这里是**校验失败**（40001），而不是"相当于全部已读"。
 */
@Data
public class NotificationReadDTO {

    @NotEmpty(message = "通知 ID 列表不能为空")
    private List<Long> ids;
}
