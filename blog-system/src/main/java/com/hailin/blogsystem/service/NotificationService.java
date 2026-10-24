package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.NotificationType;
import com.hailin.blogsystem.entity.vo.NotificationVO;
import com.hailin.blogsystem.entity.vo.PageVO;

import java.util.List;

/**
 * 站内通知（设计稿：docs/notification/通知中心设计.md）。
 *
 * <p><b>写通知是"业务事务的一部分"，不是异步任务</b>：{@link #notify} 由点赞 / 收藏 / 评论 / 关注
 * 的业务方法在它**自己的事务里同步调用**，业务回滚通知一起回滚。
 * 这样"业务成功 ⟺ 通知在"，不需要补偿、不需要对账。
 *
 * <p>也正因如此，{@link #notify} **刻意不抛异常、不返回结果**：
 * 通知写不进去（幂等冲突、参数不全、不给自己发）绝不该让用户的点赞失败。
 */
public interface NotificationService {

    /**
     * 写一条通知（幂等）。**必须在调用方的事务内调用**。
     *
     * <p>幂等靠唯一键 {@code (receiver_id, type, actor_id, target_id)}：
     * 同一人对同一对象的同类事件只会有一条，重复调用静默忽略（不抛异常、不覆盖原行）。
     *
     * <p>以下情况**静默跳过**（都是正常分支，不是错误）：
     * <ul>
     *   <li>接收者 == 触发者（自己评论自己的文章，不该给自己发通知）</li>
     *   <li>任一必需参数为空</li>
     *   <li>唯一键冲突（连点两次赞、取消后再赞——防刷）</li>
     * </ul>
     *
     * @param receiverId 接收者用户 id
     * @param actorId    触发者用户 id
     * @param type       事件类型
     * @param targetId   事件指向的对象：文章 id / **新评论 id** / 被关注者 id（语义见设计稿 §4.3）
     * @param articleId  跳转用文章 id（关注类事件传 {@code null}）
     */
    void notify(Long receiverId, Long actorId, NotificationType type, Long targetId, Long articleId);

    /** 我的通知列表（传统分页，按创建时间倒序） */
    PageVO<NotificationVO> listMine(Long page, Long pageSize);

    /** 我的未读数（header 红点用） */
    long unreadCount();

    /** 标记**我自己的**指定通知为已读。传别人的 id 进来是 0 行，不是报错 */
    void markRead(List<Long> ids);

    /** 把我的全部未读标记为已读 */
    void markAllRead();
}
