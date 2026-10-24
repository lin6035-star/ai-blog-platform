package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AiWorkflowRunMapper extends BaseMapper<AiWorkflowRun> {

    @Select("""
            SELECT w.*
            FROM ai_workflow_runs w
            INNER JOIN ai_agent_runs r ON r.id = w.task_run_id
            WHERE w.task_run_id IS NOT NULL
              AND w.task_step_id IS NOT NULL
              AND w.status IN ('COMPLETED', 'CANCELLED', 'FAILED')
              AND r.status = 'WAITING_WORKFLOW'
            ORDER BY w.id ASC
            LIMIT #{limit}
            """)
    List<AiWorkflowRun> selectTerminalLinkedToWaitingTaskRuns(@Param("limit") int limit);

    @Update("""
            UPDATE ai_workflow_runs
            SET version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND version = #{version}
            """)
    int claimAction(
            @Param("id") Long id,
            @Param("version") Integer version
    );

}
