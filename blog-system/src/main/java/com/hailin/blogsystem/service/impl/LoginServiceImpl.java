package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.LoginDTO;
import com.hailin.blogsystem.entity.dto.RegisterDTO;
import com.hailin.blogsystem.entity.vo.AuthVO;
import com.hailin.blogsystem.entity.vo.UsersVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.LoginMapper;
import com.hailin.blogsystem.security.CaptchaVerifier;
import com.hailin.blogsystem.security.LoginAttemptLimiter;
import com.hailin.blogsystem.security.TokenBlacklist;
import com.hailin.blogsystem.service.LoginService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.AliyunOSSOperator;
import com.hailin.blogsystem.utils.EmailUtils;
import com.hailin.blogsystem.utils.PasswordPolicy;
import com.hailin.blogsystem.utils.JwtUtil;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LoginServiceImpl extends ServiceImpl<LoginMapper, Users>
        implements LoginService {

    private final JwtUtil jwtUtil;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final TokenBlacklist tokenBlacklist;
    private final WalletService walletService;
    private final CaptchaVerifier captchaVerifier;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 「记住我」的两种有效期。**时长由服务端定，客户端只传意愿**——
     * 让前端传秒数等于把令牌策略交给调用方，以后改天数还得跟着改前端。
     */
    @Value("${jwt.expire.long}")
    private Duration longTokenTtl;

    @Value("${jwt.expire.short}")
    private Duration shortTokenTtl;

    /**
     * 账号不存在时用来"陪跑"的 bcrypt hash：类加载时现算一个，
     * 不硬编码，也不需要它对应任何真实密码。
     */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("timing-equalizer");

    @Override
    @Transactional
    public AuthVO register(RegisterDTO registerDTO) {
        validateRegisterDTO(registerDTO);

        // 长度由 DTO 的 @Size 管；这里只查弱密码表（声明式注解做不了集合比对）。
        // 不查的话，攻击者用泄露库里的弱密码撞库命中率极高——限流挡得住"同一账号反复试"，
        // 挡不住"每个账号试一两次"的撞库。
        String weakReason = PasswordPolicy.validate(registerDTO.getPassword());
        if (weakReason != null) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, weakReason);
        }

        // 注册同样要过验证码：不限流的话，批量刷号（占用户名 / 薅注册赠送额度）比撞库更划算
        captchaVerifier.verify(registerDTO.getCaptchaVerification());

        Users users = lambdaQuery()
                .eq(Users::getUsername, registerDTO.getUsername())
                .eq(Users::getLoginType, "password")
                .one();
        if(users != null){
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "用户名已存在");
        }

        // 邮箱也要唯一：它是找回密码的唯一通道，重复了「重置链接该发给谁」就说不清了。
        // **归一化后再比**：`A@x.com` 与 `a@x.com` 是同一个邮箱，不归一化的话两个都能注册，
        // 而找回时只命中其中一个（与 LoginAttemptLimiter 对用户名做归一化同一个理由）。
        String email = EmailUtils.normalize(registerDTO.getEmail());
        Users emailOwner = lambdaQuery().eq(Users::getEmail, email).one();
        if (emailOwner != null) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "该邮箱已被注册");
        }

        Users user = new Users();
        user.setUsername(registerDTO.getUsername());
        user.setNickname(registerDTO.getNickname());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(registerDTO.getPassword()));
        user.setLoginType("password");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        save(user);
        // 与创建用户同一事务：送礼失败则用户一起回滚，用户能重新注册。
        // 反过来「用户建出来了却报注册失败」会让用户名被自己占死，重试都重试不了。
        walletService.grantInitialCredit(user.getId());

        // 刚注册的人必然在自己的设备上，给长值——否则第二天就要重新登录，体验很差
        return buildAuthVO(user, longTokenTtl);
    }

    @Override
    public AuthVO login(LoginDTO loginDTO, String clientIp) {
        validateLoginDTO(loginDTO);

        // 验证码排在**限流之前**：没通过验证码的请求不代表密码错，不该计入失败次数。
        // 顺序反过来的话，任何人拿别人的用户名连刷无效凭证，就能把真实账号锁死 15 分钟
        //（LoginAttemptLimiter 的已知残留代价就这么被放大了）。
        // 凭证是一次性的（消费即销毁），所以这里过了就过了，后续密码错了也不会退还。
        captchaVerifier.verify(loginDTO.getCaptchaVerification());

        // BCrypt 对 >72 字节的密码会抛 IllegalArgumentException（实测 Spring Security 6 的行为）。
        // 正常注册的密码不可能超长（PasswordPolicy 拦了），但**攻击者可以随便构造**——
        // 不拦的话一次请求就换来一个 500。这里按"密码错误"处理，和其它失败路径一致。
        if (PasswordPolicy.exceedsBcryptLimit(loginDTO.getPassword())) {
            throw loginFailed(loginDTO.getUsername(), clientIp);
        }

        // 查库之前先拦：锁定时既不做 bcrypt 计算，也不让"是否被锁"成为账号存在性的探针
        loginAttemptLimiter.checkBlocked(loginDTO.getUsername(), clientIp);

        Users users = lambdaQuery()
                .eq(Users::getUsername, loginDTO.getUsername())
                .eq(Users::getLoginType, "password")
                .one();

        if (users == null) {
            // 账号不存在时也跑一次 bcrypt：否则"立即返回"与"等 ~100ms"的时间差
            // 本身就是账号是否存在的探针，与下面泛化的提示语自相矛盾
            passwordEncoder.matches(loginDTO.getPassword(), DUMMY_HASH);
            throw loginFailed(loginDTO.getUsername(), clientIp);
        }

        if (!passwordEncoder.matches(loginDTO.getPassword(), users.getPasswordHash())) {
            throw loginFailed(loginDTO.getUsername(), clientIp);
        }

        // 只清账号维度：IP 维度表达的是"这个来源可疑"，成功登录证明不了同一出口下的其他人（详见类注释）
        loginAttemptLimiter.clear(loginDTO.getUsername());

        // 「记住我」= **有效期不同**，不是把密码存进浏览器：
        // 勾选 30 天、不勾 24 小时。null 按"不记住"处理，理由见 LoginDTO#rememberMe
        Duration ttl = Boolean.TRUE.equals(loginDTO.getRememberMe()) ? longTokenTtl : shortTokenTtl;

        return buildAuthVO(users, ttl);
    }

    @Override
    public void logout(String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }

        JwtUtil.Payload payload;
        try {
            payload = jwtUtil.parsePayload(token);
        } catch (Exception e) {
            // token 无效或已过期：它本来就用不了了，不需要（也无法）拉黑
            return;
        }

        // 黑名单只需活到 token 自然过期为止
        long remainingSeconds = payload.expireAtSeconds() - Instant.now().getEpochSecond();
        tokenBlacklist.revoke(payload.jti(), Duration.ofSeconds(remainingSeconds));
    }

    /**
     * 登录失败：计数与提示语绑在一处，保证两个分支（账号不存在 / 密码错误）永远一致——
     * 计数漏一个分支会变成账号存在性的探针，提示语不一致则等于直接告诉攻击者"这个账号有"。
     */
    private BusinessException loginFailed(String username, String clientIp) {
        loginAttemptLimiter.recordFailure(username, clientIp);
        return new BusinessException(BlogConstants.ErrorCode.LOGIN_FAILED, "用户名或密码错误");
    }

    private final AliyunOSSOperator aliyunOSSOperator;

    @Override  //文件上传OSS
    public UsersVO uploadAvatar(MultipartFile file) throws Exception {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录");
        }

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择头像文件");
        }

        String contentType = file.getContentType();
        if(contentType == null || !contentType.startsWith("image/")){
            throw new IllegalArgumentException("只能上传图片文件");
        }

        long maxSize = 10*1024*1024;
        if(file.getSize() > maxSize){
            throw new IllegalArgumentException("头像不能超过 10MB,太大了啊！");
        }

        Users user = getById(userId);
        if(user == null){
            throw new IllegalArgumentException("用户不存在");
        }

        String avatarUrl = aliyunOSSOperator.uploadAvatar(
                userId,
                file.getBytes(),
                file.getOriginalFilename()
        );

        user.setAvatarUrl(avatarUrl);

        //只做局部更新：updateById(user) 是整行回写，若上传期间用户改了密码 / 重置了密码
        //（password_hash、token_version 已变），这里会把上传前读到的旧值写回去——
        //改密被静默回滚、旧 token 复活。只 SET 头像和 updated_at。
        boolean updated = lambdaUpdate()
                .eq(Users::getId, userId)
                .set(Users::getAvatarUrl, avatarUrl)
                .set(Users::getUpdatedAt, LocalDateTime.now())
                .update();
        if (!updated) {
            throw new IllegalStateException("头像更新失败，请稍后重试");
        }

        return UsersVO.from(user);
    }


    @Override
    public String uploadArticleImage(MultipartFile file) throws Exception {

        Long userId = UserContext.get();

        if (userId == null) {
            throw new BusinessException(BlogConstants.ErrorCode.UNAUTHORIZED,"请先登录");
        }

        if (file == null || file.isEmpty()) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,"请选择图片文件");
        }

        //只允许图片上传
        String contentType = file.getContentType();
        if(contentType == null || !contentType.startsWith("image/")){
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,"只能上传图片文件");
        }

        //大小限制
        if(file.getSize() > 10*1024*1024){
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,"图片不能超过 10MB");
        }

        String url = aliyunOSSOperator.uploadArticleImage(
                userId,
                file.getBytes(),
                file.getOriginalFilename()
        );
        return url;
    }


    /**
     * @param ttl 本次签发的有效期：注册走长值，登录按「记住我」选
     *            （时长定义在 application.yml 的 {@code jwt.expire}）
     */
    private AuthVO buildAuthVO(Users user, Duration ttl) {
        AuthVO authVO = new AuthVO();
        authVO.setToken(jwtUtil.generateToken(user.getId(), user.tokenVersionOrDefault(), ttl));
        authVO.setUsersVO(UsersVO.from(user));
        return authVO;
    }

    private void validateRegisterDTO(RegisterDTO registerDTO) {
        if (registerDTO == null) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "请求参数不能为空");
        }
        if (!StringUtils.hasText(registerDTO.getUsername())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        if (!StringUtils.hasText(registerDTO.getNickname())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "昵称不能为空");
        }
        if (!StringUtils.hasText(registerDTO.getPassword())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "密码不能为空!!!");
        }
        if (!StringUtils.hasText(registerDTO.getConfirmPassword())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "确认密码不能为空");
        }
        if (!registerDTO.getPassword().equals(registerDTO.getConfirmPassword())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "两次密码不一致！");
        }
    }

    private void validateLoginDTO(LoginDTO loginDTO) {
        if (loginDTO == null) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "请求参数不能为空");
        }
        if (!StringUtils.hasText(loginDTO.getUsername())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        if (!StringUtils.hasText(loginDTO.getPassword())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "密码不能为空");
        }
    }
}
