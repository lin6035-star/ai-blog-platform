package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.MockPayDTO;
import com.hailin.blogsystem.entity.dto.WalletCustomRechargeDTO;
import com.hailin.blogsystem.entity.dto.WalletRechargeDTO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.PaymentInitiateVO;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletBillEntryVO;
import com.hailin.blogsystem.entity.vo.WalletCustomConfigVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.entity.vo.WalletVO;
import com.hailin.blogsystem.service.WalletRechargeNotifyService;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.PageParamUtils;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 钱包接口。
 *
 * 设计稿：docs/redis/充值订单真实化设计.md
 *
 * <p>充值相关全部需要登录（{@code /api/wallet/**} 挂在 JwtInterceptor 上）——
 * 游客没有钱包，也没有计费单元。**唯一例外是渠道回调** {@code /recharge/notify/**}：
 * 真实支付里渠道不可能带用户 JWT，它走验签而不是登录态。
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final WalletRechargeService walletRechargeService;
    private final WalletRechargeNotifyService walletRechargeNotifyService;

    /** 当前余额 + 进行中的预扣笔数（负余额的两种含义靠这两个字段区分，见 {@link WalletVO}） */
    @GetMapping
    public Result<WalletVO> getWallet() {
        return Result.success(walletService.getWallet(currentUserId()));
    }

    /**
     * 账单（**聚合视角**）。
     *
     * <p>一次 AI 调用只出现一条净额，不带预扣 / 退回的中间态——那些是记账的实现细节，
     * 用户要看的是「充了多少、花了多少」。
     */
    @GetMapping("/bill")
    public Result<PageVO<WalletBillEntryVO>> listBill(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize) {
        long safePage = PageParamUtils.clampPage(page);
        long safePageSize = PageParamUtils.clampPageSize(pageSize, 10);
        return Result.success(walletService.listBill(currentUserId(), safePage, safePageSize));
    }

    /** 充值套餐（来自 blog.wallet.packages 配置） */
    @GetMapping("/packages")
    public Result<List<WalletPackageVO>> listPackages() {
        return Result.success(walletRechargeService.listPackages());
    }

    /**
     * 自定义充值的配置（上下限 + 兑换比例）。
     *
     * <p>给前端做输入提示用。前端硬编码这两个数的话，改了配置前端不同步，
     * 用户按提示填了却被后端拒——两边都显得没道理。
     */
    @GetMapping("/recharge/custom-config")
    public Result<WalletCustomConfigVO> getCustomConfig() {
        return Result.success(walletRechargeService.getCustomConfig());
    }

    /** 下单：只传 packageCode，金额与到账额度由后端按套餐决定；返回订单含支付截止时间 */
    @PostMapping("/recharge/orders")
    public Result<RechargeOrderVO> createOrder(@RequestBody WalletRechargeDTO dto) {
        return Result.success(walletRechargeService.createOrder(dto.getPackageCode()));
    }

    /**
     * 自定义金额下单。
     *
     * <p>刻意独立成一个接口、而不是给 {@link WalletRechargeDTO} 加一个可选金额字段：
     * 套餐入口「请求体里根本没有金额字段」是靠<b>类型</b>保证的（不可能发生），
     * 合并后变成靠 if 判断（可能发生）——在钱的路径上那是降级。
     *
     * <p>支付走的是同一个 {@code /recharge/orders/{orderNo}/pay}，不另开支付路径。
     */
    @PostMapping("/recharge/orders/custom")
    public Result<RechargeOrderVO> createCustomOrder(@RequestBody WalletCustomRechargeDTO dto) {
        return Result.success(walletRechargeService.createCustomOrder(dto.getPayYuan()));
    }

    /**
     * 发起支付（预支付）：创建/复用一条支付单，返回支付凭证。
     *
     * <p>订单此时仍是 PENDING——真实支付里「发起支付」与「入账」之间隔着用户操作与渠道回调。
     */
    @PostMapping("/recharge/orders/{orderNo}/pay")
    public Result<PaymentInitiateVO> initiatePayment(@PathVariable String orderNo) {
        return Result.success(walletRechargeService.initiatePayment(orderNo));
    }

    /**
     * 模拟支付页确认付款（仅虚拟渠道）。
     *
     * <p>服务端会以渠道身份构造签名回调并走真实的回调处理链路（验签 / 幂等 / 金额校验），
     * 而不是直接加余额——这是虚拟支付「尽量贴近真实」的关键一步。
     */
    @PostMapping("/recharge/orders/{orderNo}/mock-pay")
    public Result<RechargeOrderVO> mockPay(@PathVariable String orderNo,
                                           @RequestBody(required = false) MockPayDTO dto) {
        String payToken = dto == null ? null : dto.getPayToken();
        return Result.success(walletRechargeService.mockPay(orderNo, payToken));
    }

    /** 用户取消待支付订单；已支付订单不允许取消 */
    @PostMapping("/recharge/orders/{orderNo}/cancel")
    public Result<RechargeOrderVO> cancelOrder(@PathVariable String orderNo) {
        return Result.success(walletRechargeService.cancelOrder(orderNo));
    }

    /** 订单详情（惰性关单：打开时若已过期会当场关闭） */
    @GetMapping("/recharge/orders/{orderNo}")
    public Result<RechargeOrderVO> getOrder(@PathVariable String orderNo) {
        return Result.success(walletRechargeService.getOrder(orderNo));
    }

    /** 订单列表（待支付 / 已支付 / 已关闭） */
    @GetMapping("/recharge/orders")
    public Result<PageVO<RechargeOrderVO>> listOrders(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize) {
        long safePage = PageParamUtils.clampPage(page);
        long safePageSize = PageParamUtils.clampPageSize(pageSize, 10);
        return Result.success(walletRechargeService.listOrders(safePage, safePageSize));
    }

    /** 主动查单：回调丢失时手动补单（定时任务也会跑） */
    @PostMapping("/recharge/orders/{orderNo}/sync")
    public Result<Boolean> syncPayment(@PathVariable String orderNo) {
        return Result.success(walletRechargeService.syncPayment(orderNo));
    }

    /**
     * 渠道异步回调入口（<b>免登录</b>，靠验签自证）。
     *
     * <p>真实支付里由渠道服务器 POST 进来，报文原文 + 签名头。
     * 虚拟支付由 {@code MockPaymentChannel} 签出同构报文，走同一条处理链路。
     *
     * @param channel   渠道标识（MOCK）
     * @param payload   回调报文原文
     * @param signature 签名头（HMAC-SHA256 十六进制）
     */
    @PostMapping("/recharge/notify/{channel}")
    public Result<Void> notify(@PathVariable String channel,
                               @RequestBody String payload,
                               @RequestHeader(value = "X-Pay-Signature", required = false) String signature) {
        walletRechargeNotifyService.handleCallback(channel, payload, signature);
        return Result.success();
    }

    /**
     * 取当前用户。
     *
     * <p>这里显式抛而不是让 null 传下去：{@code /api/wallet/**} 由 JwtInterceptor 拦截，
     * 正常情况下不可能为 null。一旦为 null 说明拦截器配置被改漏了——
     * 那时应当立刻失败，而不是静默返回「余额 0」这种看着正常、实际错误的响应。
     */
    private Long currentUserId() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalStateException("未登录上下文：/api/wallet/** 应由 JwtInterceptor 拦截");
        }
        return userId;
    }
}
