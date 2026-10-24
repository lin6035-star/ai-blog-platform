package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.mapper.LearningStageMapper;
import com.hailin.blogsystem.service.LearningPlansService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 学习计划乐观锁（bug A）：覆盖已有计划时不能静默失败。
 *
 * <p>要抓的失败模式：调用方 {@code LearningPlanFlowSupport.savePlan} 构造的是
 * 「只带 id 的新实体」，version 恒为实体默认 0；而库里 version 早被
 * {@code refreshPlanStatus} 推高过。旧实现直接 {@code updateById(plan)}，
 * 生成 {@code WHERE version=0} 打不中任何行、返回值又被忽略——
 * 计划标题/目标/状态静默不更新，紧接着旧 stages 照删、新 stages 照插，
 * 数据变成「旧计划头 + 新阶段」且没有任何报错。
 *
 * <p>修复前本用例必红：stored 的 version=5，调用方传 version=0，标题会停在「旧标题」。
 */
@SpringBootTest
@Transactional
class LearningPlanOptimisticLockTests {

    @Autowired
    private LearningPlansService learningPlansService;

    @Autowired
    private LearningPlanMapper learningPlanMapper;

    @Autowired
    private LearningStageMapper learningStageMapper;

    @Test
    void staleVersionFromCallerMustNotSkipPlanFieldUpdate() {
        long planId = 9_100_001L;
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);

        // 模拟"覆盖已有计划"：库里 version 已被 refreshPlanStatus 推到 5
        LearningPlans stored = new LearningPlans();
        stored.setId(planId);
        stored.setUserId(101L);
        stored.setTitle("旧标题");
        stored.setGoal("旧目标");
        stored.setStatus(LearningPlans.STATUS_ACTIVE);
        stored.setVersion(5);
        stored.setCreatedAt(yesterday);
        stored.setUpdatedAt(yesterday);
        learningPlanMapper.insert(stored);
        learningStageMapper.insert(stage(planId, "旧阶段"));

        // LearningPlanFlowSupport.savePlan 的等价构造：只带 id，version 保持实体默认 0
        LearningPlans fresh = new LearningPlans();
        fresh.setId(planId);
        fresh.setUserId(101L);
        fresh.setTitle("新标题");
        fresh.setGoal("新目标");
        fresh.setStatus(LearningPlans.STATUS_ACTIVE);
        fresh.setCreatedAt(LocalDateTime.now());
        fresh.setUpdatedAt(LocalDateTime.now());

        learningPlansService.saveOrUpdatePlan(fresh, List.of(stage(planId, "新阶段")));

        LearningPlans reloaded = learningPlanMapper.selectById(planId);
        assertThat(reloaded.getTitle())
                .as("计划字段必须真的落库（修复前 version=0 打不中行，标题静默不更新）")
                .isEqualTo("新标题");
        assertThat(reloaded.getGoal()).isEqualTo("新目标");
        assertThat(reloaded.getVersion())
                .as("成功 CAS 一次后版本应 +1")
                .isEqualTo(6);

        List<LearningStages> stages = learningStageMapper.selectList(
                new LambdaQueryWrapper<LearningStages>().eq(LearningStages::getPlanId, planId));
        assertThat(stages)
                .as("阶段仍按覆盖式重建")
                .extracting(LearningStages::getTitle)
                .containsExactly("新阶段");
    }

    private LearningStages stage(Long planId, String title) {
        LearningStages stage = new LearningStages();
        stage.setPlanId(planId);
        stage.setOrderNum(1);
        stage.setTitle(title);
        stage.setTasks("[]");
        stage.setCreatedAt(LocalDateTime.now());
        stage.setUpdatedAt(LocalDateTime.now());
        return stage;
    }
}
