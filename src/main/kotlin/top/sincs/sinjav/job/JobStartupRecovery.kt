package top.sincs.sinjav.job

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import top.sincs.sinjav.domain.FileRecordRepository
import top.sincs.sinjav.domain.JobRepository

/**
 * 应用启动恢复：容器重建（重启/崩溃）会丢掉所有执行线程，但 jobs 表里的任务
 * 仍是 running、file_records 里被标记的记录仍是 organizing（永久残留，
 * 且 existsRunning 互斥检查会阻止再触发同类型任务）。
 * 启动完成后统一清理：running 任务标记为 failed（附中断原因），organizing 记录复位为 pending。
 */
@Component
class JobStartupRecovery(
    private val jobs: JobRepository,
    private val fileRecords: FileRecordRepository,
) {
    private val log = LoggerFactory.getLogger(JobStartupRecovery::class.java)

    /** ApplicationReadyEvent：晚于 Flyway/数据库初始化，可安全查库 */
    @EventListener(ApplicationReadyEvent::class)
    fun recover() {
        val running = jobs.findRunning()
        running.forEach { job ->
            jobs.appendError(job.id, "job", "应用重启，任务中断")
            jobs.finish(job.id, "failed")
        }
        val reset = fileRecords.resetAllOrganizingToPending()
        if (running.isNotEmpty() || reset > 0) {
            log.info(
                "启动恢复完成：{} 个残留运行中任务已标记为 failed，{} 条「整理中」记录已复位为待整理",
                running.size, reset,
            )
        }
    }
}
