package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class Users {

    @TableId( type = IdType.ASSIGN_ID)
    private Long id;
    private String username;  //用户名
    private String passwordHash;  //密码哈希
    private String nickname;  //昵称
    private String avatarUrl;  //头像地址
    private String bio;  //个人简介
    private String loginType = "password";  //登录方式: password / github
    private Long githubId;  //GitHub 用户ID
    /**
     * 邮箱：**找回密码的唯一通道**（唯一索引）。
     *
     * <p>老数据为 {@code null}——这类账号走不了找回流程，属于可接受的过渡；
     * 唯一索引允许多个 null，所以不冲突。
     */
    private String email;
    /** token 代次：改密/重置后 +1，令该用户已签发的全部 token 立即失效。见 TokenVersionStore */
    private Integer tokenVersion = 0;
    private Integer status = 1; //状态：0禁用，1正常
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "null", delval = "now()")  // null=未删除，删除时写入当前时间
    private LocalDateTime deletedAt;

    private Integer followersCount;  //粉丝数
    private Integer followingCount;  //关注数

    /**
     * 取 token 代次，null 视为 0（与列默认值一致）。
     *
     * <p>签发 token 的两处（本地登录 / GitHub 登录）都要用它：老数据回填前
     * {@code token_version} 可能是 null，直接拆箱会 NPE。
     */
    public int tokenVersionOrDefault() {
        return tokenVersion == null ? 0 : tokenVersion;
    }
}
