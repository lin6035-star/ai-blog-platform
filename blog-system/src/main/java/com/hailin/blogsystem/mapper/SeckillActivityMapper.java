package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀活动 Mapper。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.4
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 最终裁决：条件扣减库存。
     *
     * <p>⚠️ 条件里引用 {@code total_stock} <b>列</b>，不要把总库存当参数传进来——
     * 传参意味着判定依据来自调用方，一旦传入值和行内值不一致（活动被改过库存、
     * 参数拼错），限制就静默失效了。让 DB 用自己那一行做判断。
     *
     * @return 影响行数；<b>0 = DB 说已售罄</b>，这是业务失败不是技术失败，
     *         处置方式完全相反（见设计稿 §6.5）
     */
    @Update("""
            UPDATE seckill_activity
            SET sold_count = sold_count + 1
            WHERE id = #{activityId}
              AND sold_count < total_stock
            """)
    int increaseSoldCount(@Param("activityId") Long activityId);

    /**
     * 回滚库存（把上面那 +1 减回去）。
     *
     * <p>只在<b>同一事务内</b>的后续步骤失败时调用——事务一提交，
     * 「已发放」就是既成事实，那时再退库存等于把同一个名额发给两个人。
     */
    @Update("""
            UPDATE seckill_activity
            SET sold_count = sold_count - 1
            WHERE id = #{activityId}
              AND sold_count > 0
            """)
    int decreaseSoldCount(@Param("activityId") Long activityId);

    /**
     * 状态流转（CAS）：只在当前状态确实是 {@code expected} 时才推进。
     *
     * <p><b>返回 0 要静默忽略，不能当失败</b>——说明别人已经推过了（多实例、
     * 或运维手工改过）。调用方是幂等的定时任务，失败的下场只是「下一轮不用再做」。
     * 对照：用户动作型的 CAS 失败必须报错（见 {@code ArticlesServiceImpl#updateArticleVisibility}），
     * 两者方向相反，别照抄错的那个。
     *
     * <p>这把多实例下的流转变成天然安全的，<b>不需要分布式锁</b>。
     *
     * <p>⚠️ {@code status_changed_at} 显式写进语句里，而不是给列加
     * {@code ON UPDATE CURRENT_TIMESTAMP}：{@link #increaseSoldCount} 每发一份额度
     * 就 UPDATE 一行，用 updated_at 的话它每次抢购都会跳，
     * 「这次 ENDED 是超时判的还是售罄判的」就再也查不出来了。
     * 显式写还有第二个好处——MySQL 与 H2 行为一致，测试才能真正锁住它。
     */
    @Update("""
            UPDATE seckill_activity
            SET status = #{target}, status_changed_at = NOW()
            WHERE id = #{id}
              AND status = #{expected}
            """)
    int casStatus(@Param("id") Long id,
                  @Param("expected") String expected,
                  @Param("target") String target);
}
