package top.sincs.sinjav.job

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/** 任务被用户主动停止时抛出，JobRunner.wrap 捕获后把任务标记为 cancelled */
class JobCancelledException : RuntimeException("任务已被用户停止")

/**
 * 任务停止控制（协作式取消）：
 * - stop 接口把 jobId 登记进 cancelled 集合
 * - 各执行服务的处理循环每处理完一项检查一次，命中即停止后续处理
 * - JobRunner.wrap 结束时清理标记，并把任务落库为 cancelled
 * 当前正在执行的条目（刮削/下载/整理单条）会执行完，不会中途强杀线程
 */
@Service
class JobControl {

    private val log = LoggerFactory.getLogger(JobControl::class.java)
    private val cancelled: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** 登记停止请求（重复登记幂等） */
    fun requestStop(jobId: Long) {
        cancelled.add(jobId)
        log.info("已登记停止请求 jobId={}", jobId)
    }

    fun isCancelled(jobId: Long): Boolean = cancelled.contains(jobId)

    fun clear(jobId: Long) {
        cancelled.remove(jobId)
    }
}
