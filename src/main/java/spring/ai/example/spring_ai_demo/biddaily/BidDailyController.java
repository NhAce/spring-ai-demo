package spring.ai.example.spring_ai_demo.biddaily;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 招投标日报手动触发接口（联调 / 补跑 / 强制重推用）：
 * <pre>
 * POST /bid/daily/run                     —— 执行今天的批次
 * POST /bid/daily/run?date=2026-10-08     —— 补跑指定日期
 * POST /bid/daily/run?force=true          —— 当天已推送过时强制重推
 * </pre>
 * 同步执行（采集 + 汇总约需数秒到数十秒）。幂等：重复调用不会产生重复数据或重复推送。
 */
@RestController
@CrossOrigin
public class BidDailyController {

	private final BidDailyReportTask task;

	public BidDailyController(BidDailyReportTask task) {
		this.task = task;
	}

	@PostMapping("/bid/daily/run")
	public BidDailyReportTask.RunResult run(
			@RequestParam(value = "date", required = false) String date,
			@RequestParam(value = "force", defaultValue = "false") boolean force) {
		LocalDate day;
		try {
			day = (date == null || date.isBlank()) ? LocalDate.now() : LocalDate.parse(date);
		} catch (Exception e) {
			return new BidDailyReportTask.RunResult(date, 0, 0, 0, 0, false, "date 参数格式错误，应为 yyyy-MM-dd");
		}
		try {
			return task.run(day, force);
		} catch (BidDailyReportTask.BatchRunException e) {
			// 返回断点进度：已入库的不会重采、已汇总的不会重调，修复后重跑即可续传
			return new BidDailyReportTask.RunResult(
					day.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
					e.getCrawled(), e.getInserted(), e.getSummarized(), 0, false,
					"执行失败（断点已保存，修复后重跑自动续传）: " + e.getMessage());
		} catch (Exception e) {
			return new BidDailyReportTask.RunResult(
					day.format(DateTimeFormatter.ofPattern("yyyyMMdd")), 0, 0, 0, 0, false,
					"执行失败: " + e.getMessage());
		}
	}
}
