package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.vo.UsersVO;
import com.hailin.blogsystem.service.LoginService;
import com.hailin.blogsystem.utils.AliyunOSSOperator;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 头像上传（bug C）：局部更新，不能整行回写。
 *
 * <p>要抓的失败模式：旧实现先 {@code getById} 读出整行用户，OSS 上传完再
 * {@code updateById(user)} 整行回写。如果上传期间用户改了密码 / 被重置密码
 * （{@code password_hash}、{@code token_version} 已变），上传前读到的旧快照会覆盖新值——
 * 改密被静默回滚、旧 token 复活。
 *
 * <p>用 OSS 上传回调模拟「改密正好发生在读取之后、回写之前」这个窗口；修复前两条断言必红。
 */
@SpringBootTest
@Transactional
class AvatarUploadPartialUpdateTests {

    private static final Long USER_ID = 101L;
    private static final String AVATAR_URL = "https://oss.example.com/avatars/101/new.png";

    @MockBean
    private AliyunOSSOperator aliyunOSSOperator;

    @Autowired
    private LoginService loginService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void uploadAvatarMustNotOverwriteConcurrentPasswordChange() throws Exception {
        // 服务在上传前已经 getById 读走整行；上传回调里改密，制造"旧快照"窗口
        when(aliyunOSSOperator.uploadAvatar(eq(USER_ID), any(byte[].class), any()))
                .thenAnswer(invocation -> {
                    jdbcTemplate.update(
                            "update users set password_hash = ?, token_version = ? where id = ?",
                            "concurrent-new-hash", 7, USER_ID);
                    return AVATAR_URL;
                });

        UserContext.set(USER_ID);
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{1, 2, 3});

        UsersVO vo = loginService.uploadAvatar(file);

        assertThat(vo.getAvatarUrl()).isEqualTo(AVATAR_URL);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select avatar_url, password_hash, token_version from users where id = ?", USER_ID);
        assertThat(row.get("avatar_url")).isEqualTo(AVATAR_URL);
        assertThat(row.get("password_hash"))
                .as("头像上传不能把上传前读到的旧密码哈希写回去（改密被静默回滚）")
                .isEqualTo("concurrent-new-hash");
        assertThat(((Number) row.get("token_version")).intValue())
                .as("头像上传不能把旧 token_version 写回去（旧 token 复活）")
                .isEqualTo(7);
    }
}
