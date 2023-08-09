package com.lewin.luxanaipark.job;

import com.lewin.commons.utils.UUIDUtils;
import com.lewin.luxanaipark.config.BizProperties;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.stereotype.Component;

/** 定时任务自动化配置
 * @author Jun
 * @since 1.0.0
 */
@Component
public class JobInitializer {


    public JobInitializer(BizProperties bizProperties) {
        var cronJobList = bizProperties.getCronJobList();
        var factory = new StdSchedulerFactory();
        var jb = JobBuilder.newJob(TrafficJob.class)
                .withIdentity(UUIDUtils.random())
                .build();

        for (var cron : cronJobList) {
            var trigger = TriggerBuilder
                    .newTrigger()
                    .withIdentity(UUIDUtils.random())
                    .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                    .build();
            try {
                Scheduler scheduler = factory.getScheduler();
                scheduler.scheduleJob(jb, trigger);
                scheduler.start();
            } catch (SchedulerException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
